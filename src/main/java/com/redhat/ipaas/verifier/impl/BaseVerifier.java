/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.ipaas.verifier.impl;

import java.util.*;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.redhat.ipaas.verifier.Verifier;
import com.redhat.ipaas.verifier.VerifierResponse;
import org.apache.camel.*;
import org.apache.camel.impl.DefaultCamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author roland
 * @since 28/03/2017
 */
public abstract class BaseVerifier implements Verifier {

    private Logger log = LoggerFactory.getLogger(this.getClass());
    private CamelContext camel;
    private ComponentVerifier verifier;

    @PostConstruct
    public void start() throws Exception {
        camel = new DefaultCamelContext();
        camel.start();

        Component verifierComponent = camel.getComponent(getConnectorAction());
        if (verifierComponent instanceof VerifiableComponent) {
            VerifiableComponent vc = (VerifiableComponent) verifierComponent;
            verifier = vc.getVerifier();
        } else {
            verifier = null;
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        camel.stop();
    }

    // The concrete action call
    protected abstract String getConnectorAction();

    // ========================================================

    public List<VerifierResponse> verify(Map<String, Object> params) {
        if (verifier == null) {
            return Collections.singletonList(createUnsupportedResponse());
        }

        customize(params);

        // the connector must support ping check if its verifiable
        List<VerifierResponse> resp = new ArrayList<VerifierResponse>();
        for (Verifier.Scope scope :  Verifier.Scope.values()) {
            ComponentVerifier.Result result = verifier.verify(toComponentScope(scope), params);
            resp.add(toVerifierResponse(result));
            log.info("PING: {} === {}",
                     getConnectorAction(), result.getStatus());
            if (result.getStatus() == ComponentVerifier.Result.Status.ERROR) {
                log.error("{} --> ", getConnectorAction());
                for (ComponentVerifier.VerificationError error : result.getErrors()) {
                    log.error("   {} : {}", error.getCode(), error.getDescription());
                }
            }
            if (result.getStatus() == ComponentVerifier.Result.Status.ERROR ||
                result.getStatus() == ComponentVerifier.Result.Status.UNSUPPORTED) {
                break;
            }
        }
        return resp;
    }

    // Hook for customizing params
    protected void customize(Map<String, Object> params) {

    }

    private ComponentVerifier.Scope toComponentScope(Scope scope) {
        switch (scope) {
            case CONNECTIVITY:
                return ComponentVerifier.Scope.CONNECTIVITY;
            case PARAMETERS:
                return ComponentVerifier.Scope.PARAMETERS;
            default:
                throw new IllegalArgumentException("Unknown scope value " + scope);
        }
    }

    private VerifierResponse createUnsupportedResponse() {
        return new VerifierResponse.Builder(Status.UNSUPPORTED, Scope.PARAMETERS)
            .error("internal-error",
                   String.format("No action %s used for the verification known", getConnectorAction()))
            .build();
    }

    private VerifierResponse toVerifierResponse(ComponentVerifier.Result result) {
        VerifierResponse.Builder builder =
            new VerifierResponse.Builder(result.getStatus().name(),
                                         result.getScope().name());
        if (result.getErrors() != null) {
            for (ComponentVerifier.VerificationError error : result.getErrors()) {
                builder.withError(error.getCode().getName())
                         .description(error.getDescription())
                         .parameters(error.getParameterKeys())
                         .attributes(convertToStringKeyedMap(error.getDetails()))
                       .endError();
            }
        }
        return builder.build();
    }

    private Map<String, Object> convertToStringKeyedMap(Map<ComponentVerifier.VerificationError.Attribute, Object> details) {
        return details != null ?
            details.entrySet()
                   .stream()
                   .collect(Collectors.toMap(k -> k.getKey().name(), Map.Entry::getValue)) :
            null;
    }
}
