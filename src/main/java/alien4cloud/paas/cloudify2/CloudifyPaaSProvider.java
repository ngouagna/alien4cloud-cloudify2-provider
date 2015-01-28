package alien4cloud.paas.cloudify2;

import java.util.List;
import java.util.Set;

import org.cloudifysource.restclient.exceptions.RestClientException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import alien4cloud.model.cloud.CloudResourceMatcherConfig;
import alien4cloud.model.cloud.CloudResourceType;
import alien4cloud.paas.IManualResourceMatcherPaaSProvider;
import alien4cloud.paas.exception.PluginConfigurationException;

import com.google.common.collect.Sets;

@Component("cloudify-paas-provider-bean")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class CloudifyPaaSProvider extends AbstractCloudifyPaaSProvider<PluginConfigurationBean> implements IManualResourceMatcherPaaSProvider {

    private PluginConfigurationBean configurationBean = new PluginConfigurationBean();

    private List<CloudifyComputeTemplate> templates;

    @Override
    protected PluginConfigurationBean getPluginConfigurationBean() {
        return configurationBean;
    }

    @Override
    public PluginConfigurationBean getDefaultConfiguration() {
        return configurationBean;
    }

    @Override
    public void setConfiguration(PluginConfigurationBean configuration) throws PluginConfigurationException {
        this.configurationBean = configuration;
        this.cloudifyRestClientManager.setCloudifyConnectionConfiguration(configuration.getCloudifyConnectionConfiguration());
        try {
            templates = this.cloudifyRestClientManager.getRestClient().getCloudifyComputeTemplates();
        } catch (RestClientException e) {
            throw new PluginConfigurationException("Unable to retrieve compute templates from cloudify");
        }
        configureDefault();
    }

    @Override
    public void updateMatcherConfig(CloudResourceMatcherConfig cloudResourceMatcherConfig) {
        this.recipeGenerator.getPaaSResourceMatcher().configure(cloudResourceMatcherConfig);
    }

    @Override
    public String[] getAvailableResourceIds(CloudResourceType resourceType) {
        if (this.templates == null || this.templates.isEmpty()) {
            return null;
        }
        switch (resourceType) {
        case IMAGE:
            Set<String> imageIds = Sets.newHashSet();
            for (CloudifyComputeTemplate template : templates) {
                imageIds.add(template.getImageId());
            }
            return imageIds.toArray(new String[imageIds.size()]);
        case FLAVOR:
            Set<String> flavorIds = Sets.newHashSet();
            for (CloudifyComputeTemplate template : templates) {
                flavorIds.add(template.getHardwareId());
            }
            return flavorIds.toArray(new String[flavorIds.size()]);
        }
        return null;
    }

    @Override
    public String[] getAvailableResourceIds(CloudResourceType resourceType, String imageId) {
        if (this.templates == null || this.templates.isEmpty()) {
            return null;
        }
        Set<String> imageIds = Sets.newHashSet();
        for (CloudifyComputeTemplate template : templates) {
            if (imageId.equals(template.getImageId())) {
                imageIds.add(template.getHardwareId());
            }
        }
        return imageIds.toArray(new String[imageIds.size()]);
    }
}