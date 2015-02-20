package alien4cloud.paas.cloudify2.generator;

import static alien4cloud.paas.cloudify2.generator.AlienEnvironmentVariables.SERVICE_NAME;
import static alien4cloud.paas.cloudify2.generator.AlienEnvironmentVariables.SOURCE_NAME;
import static alien4cloud.paas.cloudify2.generator.AlienEnvironmentVariables.SOURCE_SERVICE_NAME;
import static alien4cloud.paas.cloudify2.generator.AlienEnvironmentVariables.TARGET_NAME;
import static alien4cloud.paas.cloudify2.generator.AlienEnvironmentVariables.TARGET_SERVICE_NAME;
import static alien4cloud.paas.cloudify2.generator.RecipeGeneratorConstants.SCRIPTS;
import static alien4cloud.paas.cloudify2.generator.RecipeGeneratorConstants.SCRIPT_LIFECYCLE;
import static alien4cloud.tosca.normative.ToscaFunctionConstants.HOST;
import static alien4cloud.tosca.normative.ToscaFunctionConstants.SELF;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Resource;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;

import alien4cloud.model.components.FunctionPropertyValue;
import alien4cloud.model.components.IOperationParameter;
import alien4cloud.model.components.ImplementationArtifact;
import alien4cloud.model.components.IndexedArtifactToscaElement;
import alien4cloud.model.components.IndexedToscaElement;
import alien4cloud.model.components.Interface;
import alien4cloud.model.components.Operation;
import alien4cloud.paas.IPaaSTemplate;
import alien4cloud.paas.cloudify2.funtion.FunctionProcessor;
import alien4cloud.paas.cloudify2.utils.CloudifyPaaSUtils;
import alien4cloud.paas.cloudify2.utils.VelocityUtil;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.plan.ToscaRelationshipLifecycleConstants;
import alien4cloud.tosca.normative.ToscaFunctionConstants;
import alien4cloud.utils.CollectionUtils;

import com.google.common.collect.Maps;

abstract class AbstractCloudifyScriptGenerator {

    @Resource
    protected FunctionProcessor funtionProcessor;
    @Resource
    protected RecipeGeneratorArtifactCopier artifactCopier;
    @Resource
    protected CommandGenerator commandGenerator;
    @Resource
    private RecipePropertiesGenerator recipePropertiesGenerator;
    @Resource
    private ApplicationContext applicationContext;

    private static final String MAP_TO_ADD_KEYWORD = "MAP_TO_ADD_";

    protected String getOperationCommandFromInterface(final RecipeGeneratorServiceContext context, final PaaSNodeTemplate nodeTemplate,
            final String interfaceName, final String operationName, ExecEnvMaps envMaps) throws IOException {
        String command = null;
        Interface interfaz = nodeTemplate.getIndexedToscaElement().getInterfaces().get(interfaceName);
        if (interfaz != null) {
            Operation operation = interfaz.getOperations().get(operationName);
            if (operation != null) {
                command = prepareAndGetCommand(context, nodeTemplate, interfaceName, operationName, envMaps, operation);
            }
        }

        return command;
    }

    protected String prepareAndGetCommand(final RecipeGeneratorServiceContext context, final IPaaSTemplate<? extends IndexedArtifactToscaElement> paaSTemplate,
            final String interfaceName, final String operationName, ExecEnvMaps envMaps, Operation operation) throws IOException {
        String command;
        command = getCommandFromOperation(context, paaSTemplate, interfaceName, operationName, operation.getImplementationArtifact(),
                operation.getInputParameters(), null, envMaps);
        if (StringUtils.isNotBlank(command)) {
            this.artifactCopier.copyImplementationArtifact(context, paaSTemplate.getCsarPath(), operation.getImplementationArtifact(),
                    paaSTemplate.getIndexedToscaElement());
        }
        return command;
    }

    protected String getCommandFromOperation(final RecipeGeneratorServiceContext context, final IPaaSTemplate<? extends IndexedToscaElement> basePaaSTemplate,
            final String interfaceName, final String operationName, final ImplementationArtifact artifact, Map<String, IOperationParameter> inputParameters,
            String instanceId, ExecEnvMaps envMaps) throws IOException {
        if (artifact == null || StringUtils.isBlank(artifact.getArtifactRef())) {
            return null;
        }

        // if relationship, add relationship env vars
        if (basePaaSTemplate instanceof PaaSRelationshipTemplate) {
            addRelationshipEnvVars(operationName, inputParameters, (PaaSRelationshipTemplate) basePaaSTemplate, context.getTopologyNodeTemplates(), instanceId,
                    envMaps);
        } else {
            addNodeEnvVars(context, (PaaSNodeTemplate) basePaaSTemplate, instanceId, inputParameters, envMaps, SELF, HOST, SERVICE_NAME);
        }

        String relativePath = CloudifyPaaSUtils.getNodeTypeRelativePath(basePaaSTemplate.getIndexedToscaElement());
        String scriptPath = relativePath + "/" + artifact.getArtifactRef();
        String operationFQN = basePaaSTemplate.getId() + "." + interfaceName + "." + operationName;
        return commandGenerator.getCommandBasedOnArtifactType(operationFQN, artifact, envMaps.runtimes, envMaps.strings, scriptPath);
    }

    private void addRelationshipEnvVars(String operationName, Map<String, IOperationParameter> inputParameters, PaaSRelationshipTemplate basePaaSTemplate,
            Map<String, PaaSNodeTemplate> builtPaaSTemplates, String instanceId, ExecEnvMaps envMaps) throws IOException {

        // funtionProcessor.processParameters(inputParameters, envMaps.strings, envMaps.runtimes, basePaaSTemplate, builtPaaSTemplates, instanceId);

        Map<String, String> sourceAttributes = Maps.newHashMap();
        Map<String, String> targetAttributes = Maps.newHashMap();
        String sourceInstanceId = getProperInstanceIdForRelEnvsBuilding(operationName, ToscaFunctionConstants.SOURCE, instanceId);
        String targetInstanceId = getProperInstanceIdForRelEnvsBuilding(operationName, ToscaFunctionConstants.TARGET, instanceId);
        String sourceId = CloudifyPaaSUtils.serviceIdFromNodeTemplateId(basePaaSTemplate.getSource());
        String targetId = CloudifyPaaSUtils.serviceIdFromNodeTemplateId(basePaaSTemplate.getRelationshipTemplate().getTarget());
        String sourceServiceName = CloudifyPaaSUtils.cfyServiceNameFromNodeTemplate(builtPaaSTemplates.get(basePaaSTemplate.getSource()));
        String targetServiceName = CloudifyPaaSUtils.cfyServiceNameFromNodeTemplate(builtPaaSTemplates.get(basePaaSTemplate.getRelationshipTemplate()
                .getTarget()));

        // separate target's from source's attributes
        if (inputParameters != null) {
            Map<String, IOperationParameter> sourceAttrParams = Maps.newHashMap();
            Map<String, IOperationParameter> targetAttrParams = Maps.newHashMap();
            Map<String, IOperationParameter> simpleParams = Maps.newHashMap();
            for (Entry<String, IOperationParameter> paramEntry : inputParameters.entrySet()) {
                if (!paramEntry.getValue().isDefinition()) {
                    if (FunctionEvaluator.isGetAttribute((FunctionPropertyValue) paramEntry.getValue())) {
                        FunctionPropertyValue param = (FunctionPropertyValue) paramEntry.getValue();
                        if (ToscaFunctionConstants.TARGET.equals(FunctionEvaluator.getEntityName(param))) {
                            targetAttrParams.put(paramEntry.getKey(), param);
                            targetAttributes.put(paramEntry.getKey(), FunctionEvaluator.getElementName(param));
                        } else if (ToscaFunctionConstants.SOURCE.equals(FunctionEvaluator.getEntityName(param))) {
                            sourceAttrParams.put(paramEntry.getKey(), param);
                            sourceAttributes.put(paramEntry.getKey(), FunctionEvaluator.getElementName(param));
                        }
                    } else {
                        simpleParams.put(paramEntry.getKey(), paramEntry.getValue());
                    }
                }
            }

            // evaluate params
            funtionProcessor.processParameters(simpleParams, envMaps.strings, envMaps.runtimes, basePaaSTemplate, builtPaaSTemplates, null);
            funtionProcessor.processParameters(sourceAttrParams, envMaps.strings, envMaps.runtimes, basePaaSTemplate, builtPaaSTemplates, sourceInstanceId);
            funtionProcessor.processParameters(targetAttrParams, envMaps.strings, envMaps.runtimes, basePaaSTemplate, builtPaaSTemplates, targetInstanceId);
        }

        // custom alien env vars
        envMaps.strings.put(SOURCE_NAME, basePaaSTemplate.getSource());
        envMaps.strings.put(TARGET_NAME, basePaaSTemplate.getRelationshipTemplate().getTarget());
        envMaps.strings.put(SOURCE_SERVICE_NAME, CloudifyPaaSUtils.cfyServiceNameFromNodeTemplate(builtPaaSTemplates.get(basePaaSTemplate.getSource())));
        envMaps.strings.put(TARGET_SERVICE_NAME,
                CloudifyPaaSUtils.cfyServiceNameFromNodeTemplate(builtPaaSTemplates.get(basePaaSTemplate.getRelationshipTemplate().getTarget())));

        // TOSCA SOURCE/SOURCES and TARGET/TARGETS
        envMaps.runtimes.put(MAP_TO_ADD_KEYWORD + ToscaFunctionConstants.SOURCE, commandGenerator.getTOSCARelationshipEnvsCommand(
                ToscaFunctionConstants.SOURCE, sourceId, sourceServiceName, sourceInstanceId, sourceAttributes));

        envMaps.runtimes.put(MAP_TO_ADD_KEYWORD + ToscaFunctionConstants.TARGET, commandGenerator.getTOSCARelationshipEnvsCommand(
                ToscaFunctionConstants.TARGET, targetId, targetServiceName, targetInstanceId, targetAttributes));
    }

    private String getProperInstanceIdForRelEnvsBuilding(String operationName, String member, String instanceId) {
        switch (operationName) {
            case ToscaRelationshipLifecycleConstants.ADD_TARGET:
            case ToscaRelationshipLifecycleConstants.REMOVE_TARGET:
                return member.equals(ToscaFunctionConstants.SOURCE) ? null : instanceId;
            case ToscaRelationshipLifecycleConstants.ADD_SOURCE:
            case ToscaRelationshipLifecycleConstants.REMOVE_SOURCE:
                return member.equals(ToscaFunctionConstants.TARGET) ? null : instanceId;
            default:
                return instanceId;
        }
    }

    protected void generateScriptWorkflow(final Path servicePath, final Path velocityDescriptorPath, final String lifecycle, final List<String> executions,
            final Map<String, ? extends Object> additionalPropeties) throws IOException {
        Path outputPath = servicePath.resolve(lifecycle + ".groovy");

        Map<String, Object> properties = Maps.newHashMap();
        properties.put(SCRIPT_LIFECYCLE, lifecycle);
        properties.put(SCRIPTS, executions);
        properties = CollectionUtils.merge(additionalPropeties, properties, true);
        VelocityUtil.writeToOutputFile(velocityDescriptorPath, outputPath, properties);
    }

    private void addNodeEnvVars(final RecipeGeneratorServiceContext context, final PaaSNodeTemplate nodeTemplate, final String instanceId,
            Map<String, IOperationParameter> inputParameters, ExecEnvMaps envMaps, String... envKeys) {
        funtionProcessor.processParameters(inputParameters, envMaps.strings, envMaps.runtimes, nodeTemplate, context.getTopologyNodeTemplates(), instanceId);
        if (envKeys != null) {
            for (String envKey : envKeys) {
                switch (envKey) {
                    case SELF:
                        envMaps.strings.put(envKey, nodeTemplate.getId());
                        break;
                    case HOST:
                        envMaps.strings.put(envKey, nodeTemplate.getParent() == null ? null : nodeTemplate.getParent().getId());
                        break;
                    case SERVICE_NAME:
                        envMaps.strings.put(envKey, CloudifyPaaSUtils.cfyServiceNameFromNodeTemplate(nodeTemplate));
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected Path loadResourceFromClasspath(String resource) throws IOException {
        URI uri = applicationContext.getResource(resource).getURI();
        String uriStr = uri.toString();
        Path path = null;
        if (uriStr.contains("!")) {
            FileSystem fs = null;
            try {
                String[] array = uriStr.split("!");
                fs = FileSystems.newFileSystem(URI.create(array[0]), new HashMap<String, Object>());
                path = fs.getPath(array[1]);

                // Hack to avoid classloader issues
                Path createTempFile = Files.createTempFile("velocity", ".vm");
                createTempFile.toFile().deleteOnExit();
                Files.copy(path, createTempFile, StandardCopyOption.REPLACE_EXISTING);

                path = createTempFile;
            } finally {
                if (fs != null) {
                    fs.close();
                }
            }
        } else {
            path = Paths.get(uri);
        }
        return path;
    }

    protected void generatePropertiesFile(RecipeGeneratorServiceContext context, PaaSNodeTemplate serviceRootTemplate) throws IOException {
        Path descriptorPath = loadResourceFromClasspath("classpath:velocity/ServiceProperties.vm");
        recipePropertiesGenerator.generatePropertiesFile(context, serviceRootTemplate, descriptorPath);
    }

    @AllArgsConstructor
    @NoArgsConstructor
    protected class ExecEnvMaps {
        Map<String, String> strings = Maps.newHashMap();
        Map<String, String> runtimes = Maps.newHashMap();
    }

}
