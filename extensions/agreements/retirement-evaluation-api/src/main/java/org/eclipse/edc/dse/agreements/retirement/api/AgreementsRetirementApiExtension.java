/********************************************************************************
 * Copyright (c) 2024 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.edc.dse.agreements.retirement.api;

import jakarta.json.Json;
import org.eclipse.edc.dse.agreements.retirement.api.transform.JsonObjectFromAgreementRetirementTransformer;
import org.eclipse.edc.dse.agreements.retirement.api.transform.JsonObjectToAgreementsRetirementEntryTransformer;
import org.eclipse.edc.dse.agreements.retirement.api.v3.AgreementsRetirementApiV3Controller;
import org.eclipse.edc.dse.agreements.retirement.spi.service.AgreementsRetirementService;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.constants.CoreConstants;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.validator.spi.JsonObjectValidatorRegistry;
import org.eclipse.edc.web.jersey.providers.jsonld.JerseyJsonLdInterceptor;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;

import java.util.Map;

import static org.eclipse.edc.dse.agreements.retirement.api.AgreementsRetirementApiExtension.NAME;


@Extension(value = NAME)
public class AgreementsRetirementApiExtension implements ServiceExtension {

    public static final String NAME = "Contract Agreement Retirement API ";

    // Matches ManagementApi.MANAGEMENT_SCOPE (org.eclipse.edc:management-api-lib) -- inlined
    // rather than adding that dependency, same as "management-api" below for MANAGEMENT_API_CONTEXT.
    private static final String MANAGEMENT_SCOPE = "MANAGEMENT_API";

    @Override
    public String name() {
        return NAME;
    }

    @Inject
    private WebService webService;
    @Inject
    private TypeTransformerRegistry transformerRegistry;
    @Inject
    private JsonObjectValidatorRegistry validator;
    @Inject
    private AgreementsRetirementService agreementsRetirementService;
    @Inject
    private Monitor monitor;
    @Inject
    private JsonLd jsonLd;
    @Inject
    private TypeManager typeManager;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var jsonFactory = Json.createBuilderFactory(Map.of());
        var managementTypeTransformerRegistry = transformerRegistry.forContext("management-api");

        managementTypeTransformerRegistry.register(new JsonObjectFromAgreementRetirementTransformer(jsonFactory));
        managementTypeTransformerRegistry.register(new JsonObjectToAgreementsRetirementEntryTransformer());

        webService.registerResource(ApiContext.MANAGEMENT, new AgreementsRetirementApiV3Controller(agreementsRetirementService, managementTypeTransformerRegistry, validator, monitor));
        // Without this, incoming request bodies never get JSON-LD expanded before reaching the
        // transformer above: a compacted body like {"edc:agreementId": "..."} keeps that exact
        // key, the transformer looks up the expanded IRI, finds nothing, and NPEs on a null
        // agreementId instead of the caller getting a clean 400 -- confirmed live on lourmel-e2e.
        webService.registerDynamicResource(ApiContext.MANAGEMENT, AgreementsRetirementApiV3Controller.class,
                new JerseyJsonLdInterceptor(jsonLd, typeManager, CoreConstants.JSON_LD, MANAGEMENT_SCOPE));
    }

}
