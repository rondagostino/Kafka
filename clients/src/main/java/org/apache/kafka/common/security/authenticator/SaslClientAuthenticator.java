/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.security.authenticator;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.NetworkClient;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.IllegalSaslStateException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.UnsupportedSaslMechanismException;
import org.apache.kafka.common.network.Authenticator;
import org.apache.kafka.common.network.NetworkSend;
import org.apache.kafka.common.network.ReauthenticationContext;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.Send;
import org.apache.kafka.common.network.TransportLayer;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.types.SchemaException;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.requests.ApiVersionsRequest;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.requests.ApiVersionsResponse.ApiVersion;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.SaslAuthenticateRequest;
import org.apache.kafka.common.requests.SaslAuthenticateResponse;
import org.apache.kafka.common.requests.SaslHandshakeRequest;
import org.apache.kafka.common.requests.SaslHandshakeResponse;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.kerberos.KerberosError;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class SaslClientAuthenticator implements Authenticator {

    public enum SaslState {
        SEND_APIVERSIONS_REQUEST,            // Initial state: client sends ApiVersionsRequest in this state when authenticating
        RECEIVE_APIVERSIONS_RESPONSE,        // Awaiting ApiVersionsResponse from server
        SEND_HANDSHAKE_REQUEST,              // Received ApiVersionsResponse, send SaslHandshake request (re-authentication starts here)
        RECEIVE_HANDSHAKE_OR_OTHER_RESPONSE, // Awaiting SaslHandshake response from server (may receive other, in-flight responses as well when re-authenticating)
        INITIAL,                             // Initial state starting SASL token exchange for configured mechanism, send first token
        INTERMEDIATE,                        // Intermediate state during SASL token exchange, process challenges and send responses
        CLIENT_COMPLETE,                     // Sent response to last challenge. If using SaslAuthenticate, wait for authentication status from server, else COMPLETE
        COMPLETE,                            // Authentication sequence complete. If using SaslAuthenticate, this state implies successful authentication.
        FAILED                               // Failed authentication due to an error at some stage
    }

    private static final Logger LOG = LoggerFactory.getLogger(SaslClientAuthenticator.class);
    private static final short DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER = -1;
    private static final Random RNG = new Random();

    private final Subject subject;
    private final String servicePrincipal;
    private final String host;
    private final String node;
    private final String mechanism;
    private final TransportLayer transportLayer;
    private final SaslClient saslClient;
    private final Map<String, ?> configs;
    private final String clientPrincipalName;
    private final AuthenticateCallbackHandler callbackHandler;

    // buffers used in `authenticate`
    private NetworkReceive netInBuffer;
    private Send netOutBuffer;

    // Current SASL state
    private SaslState saslState;
    // Next SASL state to be set when outgoing writes associated with the current SASL state complete
    private SaslState pendingSaslState;
    // Correlation ID for the next request
    private int correlationId;
    // Request header for which response from the server is pending
    private RequestHeader currentRequestHeader;
    // Version of SaslAuthenticate request/responses
    private short saslAuthenticateVersion;
    private Deque<NetworkReceive> pendingAuthenticatedReceives = new ArrayDeque<>();
    private ApiVersionsResponse apiVersionsResponse = null;
    private Long positiveSessionLifetimeMs = null;
    private boolean reauthenticating = false;
    private long reauthenticationBeginMs;
    private long authenticationEndMs;
    private final Time time;
    private Long clientSessionReauthenticationTimeMs = null;

    public SaslClientAuthenticator(Map<String, ?> configs,
                                   AuthenticateCallbackHandler callbackHandler,
                                   String node,
                                   Subject subject,
                                   String servicePrincipal,
                                   String host,
                                   String mechanism,
                                   boolean handshakeRequestEnable,
                                   TransportLayer transportLayer,
                                   Time time) {
        this.node = node;
        this.subject = subject;
        this.callbackHandler = callbackHandler;
        this.host = host;
        this.servicePrincipal = servicePrincipal;
        this.mechanism = mechanism;
        this.correlationId = -1;
        this.transportLayer = transportLayer;
        this.configs = configs;
        this.saslAuthenticateVersion = DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER;

        try {
            setSaslState(handshakeRequestEnable ? SaslState.SEND_APIVERSIONS_REQUEST : SaslState.INITIAL);

            // determine client principal from subject for Kerberos to use as authorization id for the SaslClient.
            // For other mechanisms, the authenticated principal (username for PLAIN and SCRAM) is used as
            // authorization id. Hence the principal is not specified for creating the SaslClient.
            if (mechanism.equals(SaslConfigs.GSSAPI_MECHANISM))
                this.clientPrincipalName = firstPrincipal(subject);
            else
                this.clientPrincipalName = null;

            saslClient = createSaslClient();
        } catch (Exception e) {
            throw new SaslAuthenticationException("Failed to configure SaslClientAuthenticator", e);
        }
        this.time = time;
    }

    private SaslClient createSaslClient() {
        try {
            return Subject.doAs(subject, (PrivilegedExceptionAction<SaslClient>) () -> {
                String[] mechs = {mechanism};
                LOG.debug("Creating SaslClient: client={};service={};serviceHostname={};mechs={}",
                    clientPrincipalName, servicePrincipal, host, Arrays.toString(mechs));
                return Sasl.createSaslClient(mechs, clientPrincipalName, servicePrincipal, host, configs, callbackHandler);
            });
        } catch (PrivilegedActionException e) {
            throw new SaslAuthenticationException("Failed to create SaslClient with mechanism " + mechanism, e.getCause());
        }
    }

    /**
     * Sends an empty message to the server to initiate the authentication process. It then evaluates server challenges
     * via `SaslClient.evaluateChallenge` and returns client responses until authentication succeeds or fails.
     *
     * The messages are sent and received as size delimited bytes that consists of a 4 byte network-ordered size N
     * followed by N bytes representing the opaque payload.
     */
    public void authenticate() throws IOException {
        short saslHandshakeVersion = 0;
        if (netOutBuffer != null && !flushNetOutBufferAndUpdateInterestOps())
            return;

        switch (saslState) {
            case SEND_APIVERSIONS_REQUEST:
                if (this.apiVersionsResponse == null) {
                    // Always use version 0 request since brokers treat requests with schema exceptions as GSSAPI tokens
                    ApiVersionsRequest apiVersionsRequest = new ApiVersionsRequest((short) 0);
                    send(apiVersionsRequest.toSend(node, nextRequestHeader(ApiKeys.API_VERSIONS, apiVersionsRequest.version())));
                    setSaslState(SaslState.RECEIVE_APIVERSIONS_RESPONSE);
                    break;
                } else
                    setSaslState(SaslState.RECEIVE_APIVERSIONS_RESPONSE); // fall through
            case RECEIVE_APIVERSIONS_RESPONSE:
                ApiVersionsResponse apiVersionsResponse = this.apiVersionsResponse != null ? this.apiVersionsResponse
                        : (ApiVersionsResponse) receiveKafkaResponse();
                if (apiVersionsResponse == null)
                    break;
                else {
                    saslHandshakeVersion = apiVersionsResponse.apiVersion(ApiKeys.SASL_HANDSHAKE.id).maxVersion;
                    ApiVersion authenticateVersion = apiVersionsResponse.apiVersion(ApiKeys.SASL_AUTHENTICATE.id);
                    if (authenticateVersion != null)
                        saslAuthenticateVersion((short) Math.min(authenticateVersion.maxVersion, ApiKeys.SASL_AUTHENTICATE.latestVersion()));
                    setSaslState(SaslState.SEND_HANDSHAKE_REQUEST);
                    this.apiVersionsResponse = apiVersionsResponse;
                    // Fall through to send handshake request with the latest supported version
                }
            case SEND_HANDSHAKE_REQUEST:
                SaslHandshakeRequest handshakeRequest = createSaslHandshakeRequest(saslHandshakeVersion);
                send(handshakeRequest.toSend(node, nextRequestHeader(ApiKeys.SASL_HANDSHAKE, handshakeRequest.version())));
                setSaslState(SaslState.RECEIVE_HANDSHAKE_OR_OTHER_RESPONSE);
                break;
            case RECEIVE_HANDSHAKE_OR_OTHER_RESPONSE:
                SaslHandshakeResponse handshakeResponse = (SaslHandshakeResponse) receiveKafkaResponse();
                if (handshakeResponse == null)
                    break;
                else {
                    handleSaslHandshakeResponse(handshakeResponse);
                    setSaslState(SaslState.INITIAL);
                    // Fall through and start SASL authentication using the configured client mechanism
                }
            case INITIAL:
                sendSaslClientToken(new byte[0], true);
                setSaslState(SaslState.INTERMEDIATE);
                break;
            case INTERMEDIATE:
                byte[] serverToken = receiveToken();
                boolean noResponsesPending = serverToken != null && !sendSaslClientToken(serverToken, false);
                // For versions without SASL_AUTHENTICATE header, SASL exchange may be complete after a token is sent to server.
                // For versions with SASL_AUTHENTICATE header, server always sends a response to each SASL_AUTHENTICATE request.
                if (saslClient.isComplete()) {
                    if (saslAuthenticateVersion == DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER || noResponsesPending)
                        setSaslState(SaslState.COMPLETE);
                    else
                        setSaslState(SaslState.CLIENT_COMPLETE);
                }
                break;
            case CLIENT_COMPLETE:
                byte[] serverResponse = receiveToken();
                if (serverResponse != null)
                    setSaslState(SaslState.COMPLETE);
                break;
            case COMPLETE:
                break;
            case FAILED:
                // Should never get here since exception would have been propagated earlier
                throw new IllegalStateException("SASL handshake has already failed");
        }
    }

    @Override
    public void reauthenticate(ReauthenticationContext reauthenticationContext) throws IOException {
        Authenticator previousAuthenticator = Objects.requireNonNull(reauthenticationContext).previousAuthenticator();
        if (!(previousAuthenticator instanceof SaslClientAuthenticator))
            throw new IllegalArgumentException(
                    "Invalid previous authenticator in client-side re-authentication context: " + previousAuthenticator);
        apiVersionsResponse = ((SaslClientAuthenticator) previousAuthenticator).apiVersionsResponse;
        netInBuffer = reauthenticationContext.inProgressResponse();
        reauthenticating = true;
        reauthenticationBeginMs = reauthenticationContext.reauthenticationStartMs();
        authenticate();
    }

    @Override
    public Deque<NetworkReceive> getAndClearResponsesReceivedDuringReauthentication() {
        Deque<NetworkReceive> retval = pendingAuthenticatedReceives;
        pendingAuthenticatedReceives = new ArrayDeque<>();
        return retval;
    }

    @Override
    public Long clientSessionReauthenticationTimeMs() {
        return clientSessionReauthenticationTimeMs;
    }

    @Override
    public Long reauthenticationElapsedTimeMs() {
        return reauthenticating ? Long.valueOf(authenticationEndMs - reauthenticationBeginMs) : null;
    }

    private RequestHeader nextRequestHeader(ApiKeys apiKey, short version) {
        String clientId = (String) configs.get(CommonClientConfigs.CLIENT_ID_CONFIG);
        currentRequestHeader = new RequestHeader(apiKey, version, clientId, correlationId++);
        return currentRequestHeader;
    }

    // Visible to override for testing
    protected SaslHandshakeRequest createSaslHandshakeRequest(short version) {
        return new SaslHandshakeRequest.Builder(mechanism).build(version);
    }

    // Visible to override for testing
    protected void saslAuthenticateVersion(short version) {
        this.saslAuthenticateVersion = version;
    }

    private void setSaslState(SaslState saslState) {
        if (netOutBuffer != null && !netOutBuffer.completed())
            pendingSaslState = saslState;
        else {
            this.pendingSaslState = null;
            this.saslState = saslState;
            LOG.debug("Set SASL client state to {}", saslState);
            if (saslState == SaslState.COMPLETE) {
                completeAuthentication();
                transportLayer.addInterestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void completeAuthentication() {
        authenticationEndMs = time.milliseconds();
        if (positiveSessionLifetimeMs != null) {
            // pick a random percentage between 85% and 95% for session re-authentication
            double pctWindowFactorToTakeNetworkLatencyAndClockDriftIntoAccount = 0.85;
            double pctWindowJitterToAvoidReauthenticationStormAcrossManyChannelsSimultaneously = 0.10;
            double pctToUse = pctWindowFactorToTakeNetworkLatencyAndClockDriftIntoAccount + RNG.nextDouble()
                    * pctWindowJitterToAvoidReauthenticationStormAcrossManyChannelsSimultaneously;
            clientSessionReauthenticationTimeMs = authenticationEndMs
                    + (long) (positiveSessionLifetimeMs.longValue() * pctToUse);
        }
        LOG.debug("{} at {} with {} and {}", reauthenticating ? "Re-authenticated" : "Authenticated",
                new Date(authenticationEndMs),
                clientSessionReauthenticationTimeMs == null ? "no session expiration"
                        : ("session expiration "
                                + new Date(authenticationEndMs + positiveSessionLifetimeMs.longValue())),
                clientSessionReauthenticationTimeMs == null ? "no session re-authentication"
                        : ("session re-authentication on or after "
                                + new Date(clientSessionReauthenticationTimeMs.longValue())));
    }

    /**
     * Sends a SASL client token to server if required. This may be an initial token to start
     * SASL token exchange or response to a challenge from the server.
     * @return true if a token was sent to the server
     */
    private boolean sendSaslClientToken(byte[] serverToken, boolean isInitial) throws IOException {
        if (!saslClient.isComplete()) {
            byte[] saslToken = createSaslToken(serverToken, isInitial);
            if (saslToken != null) {
                ByteBuffer tokenBuf = ByteBuffer.wrap(saslToken);
                if (saslAuthenticateVersion != DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER) {
                    SaslAuthenticateRequest request = new SaslAuthenticateRequest.Builder(tokenBuf).build(saslAuthenticateVersion);
                    tokenBuf = request.serialize(nextRequestHeader(ApiKeys.SASL_AUTHENTICATE, saslAuthenticateVersion));
                }
                send(new NetworkSend(node, tokenBuf));
                return true;
            }
        }
        return false;
    }

    private void send(Send send) throws IOException {
        try {
            netOutBuffer = send;
            flushNetOutBufferAndUpdateInterestOps();
        } catch (IOException e) {
            setSaslState(SaslState.FAILED);
            throw e;
        }
    }

    private boolean flushNetOutBufferAndUpdateInterestOps() throws IOException {
        boolean flushedCompletely = flushNetOutBuffer();
        if (flushedCompletely) {
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
            if (pendingSaslState != null)
                setSaslState(pendingSaslState);
        } else
            transportLayer.addInterestOps(SelectionKey.OP_WRITE);
        return flushedCompletely;
    }

    private NetworkReceive receivePacket() throws IOException {
        if (netInBuffer == null) netInBuffer = new NetworkReceive(node);
        netInBuffer.readFrom(transportLayer);
        NetworkReceive retval = null;
        if (netInBuffer.complete()) {
            netInBuffer.payload().rewind();
            retval = netInBuffer;
            netInBuffer = null; // reset the networkReceive as we read all the data.
        }
        return retval;
    }

    private byte[] receiveResponseOrToken() throws IOException {
        NetworkReceive receive = receivePacket();
        if (receive == null)
            return null;
        byte[] retval = new byte[receive.payload().remaining()];
        receive.payload().get(retval, 0, retval.length);
        return retval;
    }

    public KafkaPrincipal principal() {
        return new KafkaPrincipal(KafkaPrincipal.USER_TYPE, clientPrincipalName);
    }

    public boolean complete() {
        return saslState == SaslState.COMPLETE;
    }

    public void close() throws IOException {
        if (saslClient != null)
            saslClient.dispose();
    }

    private byte[] receiveToken() throws IOException {
        if (saslAuthenticateVersion == DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER) {
            return receiveResponseOrToken();
        } else {
            SaslAuthenticateResponse response = (SaslAuthenticateResponse) receiveKafkaResponse();
            if (response != null) {
                Errors error = response.error();
                if (error != Errors.NONE) {
                    setSaslState(SaslState.FAILED);
                    String errMsg = response.errorMessage();
                    throw errMsg == null ? error.exception() : error.exception(errMsg);
                }
                long sessionLifetimeMs = response.sessionLifetimeMs();
                if (sessionLifetimeMs > 0L)
                    this.positiveSessionLifetimeMs = Long.valueOf(sessionLifetimeMs);
                return Utils.readBytes(response.saslAuthBytes());
            } else
                return null;
        }
    }


    private byte[] createSaslToken(final byte[] saslToken, boolean isInitial) throws SaslException {
        if (saslToken == null)
            throw new IllegalSaslStateException("Error authenticating with the Kafka Broker: received a `null` saslToken.");

        try {
            if (isInitial && !saslClient.hasInitialResponse())
                return saslToken;
            else
                return Subject.doAs(subject, (PrivilegedExceptionAction<byte[]>) () -> saslClient.evaluateChallenge(saslToken));
        } catch (PrivilegedActionException e) {
            String error = "An error: (" + e + ") occurred when evaluating SASL token received from the Kafka Broker.";
            KerberosError kerberosError = KerberosError.fromException(e);
            // Try to provide hints to use about what went wrong so they can fix their configuration.
            if (kerberosError == KerberosError.SERVER_NOT_FOUND) {
                error += " This may be caused by Java's being unable to resolve the Kafka Broker's" +
                    " hostname correctly. You may want to try to adding" +
                    " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your client's JVMFLAGS environment." +
                    " Users must configure FQDN of kafka brokers when authenticating using SASL and" +
                    " `socketChannel.socket().getInetAddress().getHostName()` must match the hostname in `principal/hostname@realm`";
            }
            error += " Kafka Client will go to AUTHENTICATION_FAILED state.";
            //Unwrap the SaslException inside `PrivilegedActionException`
            Throwable cause = e.getCause();
            // Treat transient Kerberos errors as non-fatal SaslExceptions that are processed as I/O exceptions
            // and all other failures as fatal SaslAuthenticationException.
            if (kerberosError != null && kerberosError.retriable())
                throw new SaslException(error, cause);
            else
                throw new SaslAuthenticationException(error, cause);
        }
    }

    private boolean flushNetOutBuffer() throws IOException {
        if (!netOutBuffer.completed()) {
            netOutBuffer.writeTo(transportLayer);
        }
        return netOutBuffer.completed();
    }

    private AbstractResponse receiveKafkaResponse() throws IOException {
        NetworkReceive receive = receivePacket();
        if (receive == null)
            return null;
        byte[] responseBytes = new byte[receive.payload().remaining()];
        receive.payload().get(responseBytes, 0, responseBytes.length);
        /*
         * Account for the fact that during re-authentication there may be responses
         * arriving for requests that were sent in the past
         */
        try {
            AbstractResponse response = NetworkClient.parseResponse(ByteBuffer.wrap(responseBytes), currentRequestHeader);
            currentRequestHeader = null;
            return response;
        } catch (SchemaException | IllegalArgumentException e) {
            if (reauthenticating) {
                /*
                 * It didn't match the current request header, so it must be unrelated to
                 * re-authentication. Save it so it can be processed later.
                 */
                receive.payload().rewind();
                pendingAuthenticatedReceives.add(receive);
                return null;
            } else {
                LOG.debug("Invalid SASL mechanism response, server may be expecting only GSSAPI tokens");
                setSaslState(SaslState.FAILED);
                throw new IllegalSaslStateException("Invalid SASL mechanism response, server may be expecting a different protocol", e);
            }
        }
    }

    private void handleSaslHandshakeResponse(SaslHandshakeResponse response) {
        Errors error = response.error();
        if (error != Errors.NONE)
            setSaslState(SaslState.FAILED);
        switch (error) {
            case NONE:
                break;
            case UNSUPPORTED_SASL_MECHANISM:
                throw new UnsupportedSaslMechanismException(String.format("Client SASL mechanism '%s' not enabled in the server, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
            case ILLEGAL_SASL_STATE:
                throw new IllegalSaslStateException(String.format("Unexpected handshake request with client mechanism %s, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
            default:
                throw new IllegalSaslStateException(String.format("Unknown error code %s, client mechanism is %s, enabled mechanisms are %s",
                    response.error(), mechanism, response.enabledMechanisms()));
        }
    }

    /**
     * Returns the first Principal from Subject.
     * @throws KafkaException if there are no Principals in the Subject.
     *     During Kerberos re-login, principal is reset on Subject. An exception is
     *     thrown so that the connection is retried after any configured backoff.
     */
    static final String firstPrincipal(Subject subject) {
        Set<Principal> principals = subject.getPrincipals();
        synchronized (principals) {
            Iterator<Principal> iterator = principals.iterator();
            if (iterator.hasNext())
                return iterator.next().getName();
            else
                throw new KafkaException("Principal could not be determined from Subject, this may be a transient failure due to Kerberos re-login");
        }
    }

}
