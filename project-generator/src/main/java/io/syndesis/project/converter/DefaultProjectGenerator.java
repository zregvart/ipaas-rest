/**
 * Copyright (C) 2016 Red Hat, Inc.
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
package io.syndesis.project.converter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import io.syndesis.integration.model.Flow;
import io.syndesis.integration.model.SyndesisHelpers;
import io.syndesis.integration.model.SyndesisModel;
import io.syndesis.integration.model.steps.Endpoint;
import io.syndesis.integration.support.Strings;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.Step;
import io.syndesis.project.converter.ProjectGeneratorProperties.Templates;
import io.syndesis.project.converter.visitor.GeneratorContext;
import io.syndesis.project.converter.visitor.StepVisitor;
import io.syndesis.project.converter.visitor.StepVisitorContext;
import io.syndesis.project.converter.visitor.StepVisitorFactory;
import io.syndesis.project.converter.visitor.StepVisitorFactoryRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultProjectGenerator implements ProjectGenerator {

    private static final ObjectMapper YAML_OBJECT_MAPPER = SyndesisHelpers.createObjectMapper();

    private final MustacheFactory mf = new DefaultMustacheFactory();

    /*
    Not required for the moment, needed for local forking of a maven process
    private Mustache connectorPomMustache = mf.compile(
        new InputStreamReader(getClass().getResourceAsStream("templates/connector/pom.xml.mustache")),
        "pom.xml"
    );
    */
    private final ProjectGeneratorProperties generatorProperties;
    private final StepVisitorFactoryRegistry registry;
    private final Mustache readmeMustache;
    private final Mustache applicationJavaMustache;
    private final Mustache applicationPropertiesMustache;
    private final Mustache pomMustache;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultProjectGenerator.class);

    public DefaultProjectGenerator(ProjectGeneratorProperties generatorProperties, StepVisitorFactoryRegistry registry) throws IOException {
        this.generatorProperties = generatorProperties;
        this.registry = registry;
        this.readmeMustache = compile(generatorProperties, "README.md.mustache", "README.md");
        this.applicationJavaMustache = compile(generatorProperties, "Application.java.mustache", "Application.java");
        this.applicationPropertiesMustache = compile(generatorProperties, "application.properties.mustache", "application.properties");
        this.pomMustache = compile(generatorProperties, "pom.xml.mustache", "pom.xml");
    }

    private Mustache compile(ProjectGeneratorProperties generatorProperties, String template, String name) throws IOException {
        String overridePath = generatorProperties.getTemplates().getOverridePath();
        URL resource = null;

        if (!Strings.isEmpty(overridePath)) {
            resource = getClass().getResource("templates/" + overridePath + "/" + template);
        }
        if (resource == null) {
            resource = getClass().getResource("templates/" + template);
        }
        if (resource == null) {
            throw new IllegalArgumentException(
                String.format("Unable to find te required template (overridePath=%s, template=%s)"
                    , overridePath
                    , template
                )
            );
        }

        try (InputStream stream = resource.openStream()) {
            return mf.compile(new InputStreamReader(stream, UTF_8), name);
        }
    }

    @Override
    public Map<String, byte[]> generate(GenerateProjectRequest request) throws IOException {
        Map<String, byte[]> contents = new HashMap<>();

        for (Templates.Resource additionalResource : generatorProperties.getTemplates().getAdditionalResources()) {
            String overridePath = generatorProperties.getTemplates().getOverridePath();
            URL resource = null;

            if (!Strings.isEmpty(overridePath)) {
                resource = getClass().getResource("templates/" + overridePath + "/" + additionalResource.getSource());
            }
            if (resource == null) {
                resource = getClass().getResource("templates/" + additionalResource.getSource());
            }
            if (resource == null) {
                throw new IllegalArgumentException(
                    String.format("Unable to find te required additional resource (overridePath=%s, source=%s)"
                        , overridePath
                        , additionalResource.getSource()
                    )
                );
            }

            try {
                contents.put(additionalResource.getDestination(), Files.readAllBytes(Paths.get(resource.toURI())));
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
        }

        contents.put("README.md", generateFromRequest(request, readmeMustache));
        contents.put("src/main/java/io/syndesis/example/Application.java", generateFromRequest(request, applicationJavaMustache));
        contents.put("src/main/resources/application.properties", generateFromRequest(request, applicationPropertiesMustache));
        contents.put("src/main/resources/syndesis.yml", generateFlowYaml(contents, request));
        contents.put("pom.xml", generatePom(request.getIntegration()));

        return contents;
    }

    @Override
    public byte[] generatePom(Integration integration) throws IOException {
        Set<MavenGav> connectors = new LinkedHashSet<>();
        integration.getSteps().ifPresent(steps -> {
            for (Step step : steps) {
                if (step.getStepKind().equals(Endpoint.KIND)) {
                    step.getAction().ifPresent(action -> {
                        String[] splitGav = action.getCamelConnectorGAV().split(":");
                        if (splitGav.length == 3) {
                            connectors.add(new MavenGav(splitGav[0], splitGav[1], splitGav[2]));
                        }
                    });
                }
            }
        });
        return generateFromPomContext(new PomContext(integration.getId().orElse(""), integration.getName(), integration.getDescription().orElse(null), connectors), pomMustache);
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod") // PMD false positive
    private byte[] generateFlowYaml(Map<String, byte[]> contents, GenerateProjectRequest request) throws JsonProcessingException {
        Flow flow = new Flow();
        request.getIntegration().getSteps().ifPresent(steps -> {
            if (steps.isEmpty()) {
                return;
            }

            Queue<Step> remaining = new LinkedList<>(steps);
            Step first = remaining.remove();
            if (first != null) {
                GeneratorContext generatorContext = new GeneratorContext.Builder()
                    .generatorProperties(generatorProperties)
                    .request(request)
                    .contents(contents)
                    .flow(flow)
                    .visitorFactoryRegistry(registry)
                    .build();

                StepVisitorContext stepContext = new StepVisitorContext.Builder()
                    .index(1)
                    .step(first)
                    .remaining(remaining)
                    .build();

                visitStep(generatorContext, stepContext);
            }
        });
        SyndesisModel syndesisModel = new SyndesisModel();
        syndesisModel.addFlow(flow);
        return YAML_OBJECT_MAPPER.writeValueAsBytes(syndesisModel);
    }

    private void visitStep(GeneratorContext generatorContext, StepVisitorContext stepContext) {
        StepVisitorFactory<?> factory = registry.get(stepContext.getStep().getStepKind());

        StepVisitor visitor = factory.create(generatorContext);
        generatorContext.getFlow().addStep(visitor.visit(stepContext));
        if (stepContext.hasNext()) {
             visitStep(generatorContext, stepContext.next());
        }
    }

    private byte[] generateFromRequest(GenerateProjectRequest request, Mustache template) throws IOException {
        return generate(request, template);
    }

    private byte[] generateFromPomContext(PomContext integration, Mustache template) throws IOException {
        return generate(integration, template);
    }

    private byte[] generate(Object obj, Mustache template) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        template.execute(new OutputStreamWriter(bos, UTF_8), obj).flush();
        return bos.toByteArray();
    }



    /* default */ static class MavenGav {
        private final String groupId;

        private final String artifactId;

        private final String version;

        /* default */ MavenGav(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }
    }

    /* default */ static class PomContext {

        private final String id;

        private final String name;

        private final String description;

        private final Set<MavenGav> connectors;

        /* default */ PomContext(String id, String name, String description, Set<MavenGav> connectors) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.connectors = connectors;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Set<MavenGav> getConnectors() {
            return connectors;
        }
    }
}
