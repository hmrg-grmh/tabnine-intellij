package com.tabnine.binary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.tabnine.StaticConfig;
import com.tabnine.contracts.AutocompleteRequest;
import com.tabnine.contracts.AutocompleteResponse;
import com.tabnine.exceptions.TabNineDeadException;
import com.tabnine.exceptions.TabNineInvalidResponseException;
import com.tabnine.exceptions.TooManyConsecutiveRestartsException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import static com.tabnine.StaticConfig.*;
import static java.lang.String.format;
import static java.util.Collections.singletonMap;

public class TabNineProcess {
    private final Gson gson;
    private volatile int consecutiveRestarts = 0;
    private volatile Future<?> binaryInit = null;
    private volatile int illegalResponsesGiven = 0;

    public TabNineProcess() {
        this.gson = new GsonBuilder().create();
    }

    public synchronized void init() {
        startBinary(TabNineProcessFacade::create);
    }

    /**
     * Restarts the binary's process. This is thread-safe. Should be called on start, and could be called to restart
     * the binary.
     */
    public synchronized void restart() {
        // In case of a restart already underway, no need to restart again. Just wait for it...
        if (!binaryInit.isDone()) {
            return;
        }

        if (++this.consecutiveRestarts > StaticConfig.CONSECUTIVE_RESTART_THRESHOLD) {
            Logger.getInstance(getClass()).error("Tabnine is not able to function properly", new TooManyConsecutiveRestartsException());
        }


        startBinary(TabNineProcessFacade::restart);
    }

    private void startBinary(SideEffectExecutor onStartBinaryAttempt) {
        binaryInit = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    for (int attempt = 0; shouldTryStartingBinary(attempt); attempt++) {
                        try {
                            onStartBinaryAttempt.execute();

                            break;
                        } catch (IOException e) {
                            Logger.getInstance(getClass()).warn("Error restarting TabNine. Will try again.", e);

                            try {
                                sleepUponFailure(attempt);
                            } catch (InterruptedException e2) {
                                PluginManager.processException(e);
                                break;
                            }
                        }
                    }
                });
    }

    public synchronized boolean isReady() {
        return this.binaryInit.isDone();
    }

    /**
     * Request a prediction from TabNine's binary.
     *
     * @param request
     * @return an AutocompleteResponse
     * @throws TabNineDeadException if process's dead.
     * @throws TabNineDeadException if process's BufferedReader has reached its end (also mean dead...).
     * @throws TabNineDeadException if there was an IOException communicating to the process.
     * @throws TabNineDeadException if the result from the process was invalid multiple times.
     */
    public synchronized AutocompleteResponse request(AutocompleteRequest request) throws TabNineDeadException {
        try {
            if (TabNineProcessFacade.isDead()) {
                throw new TabNineDeadException("Binary is dead");
            }

            int correlationId = TabNineProcessFacade.getAndIncrementCorrelationId();

            sendRequest(serializeRequest(request, correlationId));

            return readResult(request, correlationId);
        } catch (IOException e) {
            Logger.getInstance(getClass()).warn("Exception communicating with the binary!", e);

            throw new TabNineDeadException(e);
        } catch (TabNineInvalidResponseException e) {
            Logger.getInstance(getClass()).warn("", e);

            return null;
        }

    }

    @NotNull
    private AutocompleteResponse readResult(AutocompleteRequest request, int correlationId) throws IOException, TabNineDeadException, TabNineInvalidResponseException {
        while (true) {
            String rawResponse = TabNineProcessFacade.readLine();

            try {
                AutocompleteResponse response = gson.fromJson(rawResponse, request.response());

                if (response.correlation_id == null) {
                    Logger.getInstance(getClass()).warn("Binary is not returning correlation id (grumpy old version?)");
                    onValidResult();

                    return response;
                }

                if (response.correlation_id == correlationId) {
                    if (!request.validate(response)) {
                        throw new TabNineInvalidResponseException();
                    }

                    onValidResult();

                    return response;
                } else if (response.correlation_id > correlationId) {
                    // This should not happen, as the requests are sequential, but if it occurs, we might as well restart the binary.
                    // If this happens to users, a better readResponse that can lookup the past should be implemented.
                    throw new TabNineDeadException(
                            format("Response from the future received (recieved %d, currently at %d)",
                                    response.correlation_id, correlationId)
                    );
                }
            } catch (JsonSyntaxException | TabNineInvalidResponseException e) {
                Logger.getInstance(getClass()).warn(format("Binary returned illegal response: %s", rawResponse), e);

                if (++illegalResponsesGiven > ILLEGAL_RESPONSE_THRESHOLD) {
                    illegalResponsesGiven = 0;
                    throw new TabNineDeadException("Too many illegal responses given");
                } else {
                    throw new TabNineInvalidResponseException(e);
                }
            }
        }
    }

    @NotNull
    private String serializeRequest(AutocompleteRequest request, int correlationId) {
        Map<String, Object> jsonObject = new HashMap<>();

        jsonObject.put("version", StaticConfig.BINARY_PROTOCOL_VERSION);
        jsonObject.put("request", singletonMap(request.name(), request.withCorrelationId(correlationId)));

        return gson.toJson(jsonObject) + "\n";
    }

    private void sendRequest(String request) throws IOException {
        TabNineProcessFacade.writeRequest(request);
    }

    /**
     * Reset restart counter once a valid response is sent. This way, we only stop retrying to restart tabnine if there
     * is a chain of failures.
     */
    private void onValidResult() {
        this.consecutiveRestarts = 0;
        this.illegalResponsesGiven = 0;
    }
}