package io.jenkins.plugins;

import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;
import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.AbstractList;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QiniuStore extends AbstractDescribableImpl<QiniuStore> implements ExtensionPoint, Serializable {
    private static final Logger LOG = Logger.getLogger(QiniuStore.class.getName());

    @DataBoundConstructor
    public QiniuStore() {
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public static QiniuArtifactManagerFactory getQiniuArtifactManagerFactory() {
        return Global.getQiniuArtifactManagerFactory();
    }

    private static final class Global {
        private static QiniuArtifactManagerFactory qiniuArtifactManagerFactory = null;

        private static QiniuArtifactManagerFactory getQiniuArtifactManagerFactory() {
            if (Global.qiniuArtifactManagerFactory != null) {
                setupQiniuArtifactManagerFactory(Global.qiniuArtifactManagerFactory);
            }
            return Global.qiniuArtifactManagerFactory;
        }

        private static void setQiniuArtifactManagerFactory(QiniuArtifactManagerFactory factory) {
            if (Global.qiniuArtifactManagerFactory != null) {
                Global.qiniuArtifactManagerFactory.setAccessKey(factory.getAccessKey());
                Global.qiniuArtifactManagerFactory.setSecretKey(factory.getSecretKey());
                Global.qiniuArtifactManagerFactory.setBucketName(factory.getBucketName());
                Global.qiniuArtifactManagerFactory.setObjectNamePrefix(factory.getObjectNamePrefix());
                Global.qiniuArtifactManagerFactory.setDownloadDomain(factory.getDownloadDomain());
                Global.qiniuArtifactManagerFactory.setUseHTTPs(factory.isUseHTTPs());
            } else {
                Global.qiniuArtifactManagerFactory = factory;
            }
            setupQiniuArtifactManagerFactory(factory);
        }

        private static void setupQiniuArtifactManagerFactory(QiniuArtifactManagerFactory newFactory) {
            final AbstractList<ArtifactManagerFactory> factories = ArtifactManagerConfiguration.get().getArtifactManagerFactories();
            for (ArtifactManagerFactory factory : factories) {
                if (factory instanceof QiniuArtifactManagerFactory) {
                    LOG.log(Level.INFO, "QiniuArtifactManagerFactory was setuped");
                    return;
                }
            }

            factories.add(newFactory);
            LOG.log(Level.INFO, "QiniuArtifactManagerFactory was created and setuped");
        }

    }

    @Symbol("Qiniu")
    @Extension
    public static final class DescriptorImpl extends Descriptor<QiniuStore> {
        private String accessKey, secretKey, bucketName, objectNamePrefix, downloadDomain;
        private String rsDomain = Configuration.defaultRsHost,
                       ucDomain = Configuration.defaultUcHost,
                       apiDomain = Configuration.defaultApiHost;
        private boolean useHTTPs = false;

        public DescriptorImpl() {
            super(QiniuStore.class);
            load();
            setupArtifactManagerFactory();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            this.accessKey = json.getString("accessKey");
            this.secretKey = json.getString("secretKey");
            this.bucketName = json.getString("bucketName");
            this.objectNamePrefix = json.getString("objectNamePrefix");
            this.downloadDomain = json.getString("downloadDomain");
            this.rsDomain = json.getString("rsDomain");
            this.ucDomain = json.getString("ucDomain");
            this.apiDomain = json.getString("apiDomain");
            this.useHTTPs = json.getBoolean("useHTTPs");
            autoSetBaseURL();

            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err != null) {
                throw new FormException(Messages.QiniuStore_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName(), err, "bucketName");
            }
            save();
            setupArtifactManagerFactory();
            return super.configure(req, json);
        }

        private void autoSetBaseURL() {
            if (this.downloadDomain == null || this.downloadDomain.isEmpty()) {
                try {
                    final String[] domains = this.getBucketManager().domainList(this.bucketName);
                    if (domains.length > 0) {
                        LOG.log(Level.INFO, "Qiniu stores detected downloadDomain was not configured, it will be {0}", domains[0]);
                        this.downloadDomain = domains[0];
                    }
                } catch (QiniuException e) {
                    LOG.log(Level.WARNING, "Qiniu stores cannot decide downloadDomain for bucket {0}: {1}", new Object[]{this.bucketName, e});
                }
            }
        }

        private void setupArtifactManagerFactory() {
            if (Global.qiniuArtifactManagerFactory != null) {
                return;
            }

            if (this.accessKey == null || this.accessKey.isEmpty() ||
                    this.secretKey == null || this.secretKey.isEmpty() ||
                    this.bucketName == null || this.bucketName.isEmpty()) {
                return;
            }

            if (this.downloadDomain == null) {
                this.downloadDomain = "";
            }
            if (this.rsDomain == null) {
                this.rsDomain = Configuration.defaultRsHost;
            }
            if (this.ucDomain == null) {
                this.ucDomain = Configuration.defaultUcHost;
            }
            if (this.apiDomain == null) {
                this.apiDomain = Configuration.defaultApiHost;
            }
            if (this.objectNamePrefix == null) {
                this.objectNamePrefix = "";
            }

            final QiniuArtifactManagerFactory factory = new QiniuArtifactManagerFactory(
                    this.accessKey, this.secretKey, this.bucketName, this.objectNamePrefix,
                    this.downloadDomain, this.useHTTPs);
            Global.setQiniuArtifactManagerFactory(factory);
        }

        public FormValidation doCheckAccessKey(@QueryParameter String accessKey) throws IOException, ServletException {
            if (accessKey.isEmpty()) {
                return FormValidation.error(Messages.QiniuStore_DescriptorImpl_errors_accessKeyIsEmpty());
            }
            this.accessKey = accessKey;
            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err == null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName());
            }
        }

        public FormValidation doCheckSecretKey(@QueryParameter String secretKey) throws IOException, ServletException {
            if (secretKey.isEmpty()) {
                return FormValidation.error(Messages.QiniuStore_DescriptorImpl_errors_secretKeyIsEmpty());
            }
            this.secretKey = secretKey;
            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err == null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName());
            }
        }

        public FormValidation doCheckBucketName(@QueryParameter String bucketName) throws IOException, ServletException {
            if (bucketName.isEmpty()) {
                return FormValidation.error(Messages.QiniuStore_DescriptorImpl_errors_bucketNameIsEmpty());
            }
            this.bucketName = bucketName;
            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err == null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName());
            }
        }

        public FormValidation doCheckDownloadDomain(@QueryParameter String downloadDomain) throws IOException, ServletException {
            if (!downloadDomain.isEmpty()) {
                try {
                    URI.create("http://" + downloadDomain);
                } catch (IllegalArgumentException err) {
                    return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidDownloadDomain());
                }
            }
            this.downloadDomain = downloadDomain;
            return FormValidation.ok();
        }

        public FormValidation doCheckRsDomain(@QueryParameter String rsDomain) throws IOException, ServletException {
            if (!rsDomain.isEmpty()) {
                try {
                    URI.create("http://" + rsDomain);
                } catch (IllegalArgumentException err) {
                    return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidRsDomain());
                }
            }
            this.rsDomain = rsDomain;
            return FormValidation.ok();
        }

        public FormValidation doCheckUcDomain(@QueryParameter String ucDomain) throws IOException, ServletException {
            if (!ucDomain.isEmpty()) {
                try {
                    URI.create("http://" + ucDomain);
                } catch (IllegalArgumentException err) {
                    return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidUcDomain());
                }
            }
            this.ucDomain = ucDomain;
            return FormValidation.ok();
        }

        public FormValidation doCheckAPIDomain(@QueryParameter String apiDomain) throws IOException, ServletException {
            if (!apiDomain.isEmpty()) {
                try {
                    URI.create("http://" + apiDomain);
                } catch (IllegalArgumentException err) {
                    return FormValidation.error(err, Messages.QiniuStore_DescriptorImpl_errors_invalidAPIDomain());
                }
            }
            this.apiDomain = apiDomain;
            return FormValidation.ok();
        }

        private Throwable checkAccessKeySecretKeyAndBucketName() {
            if (this.accessKey != null && !this.accessKey.isEmpty() &&
                    this.secretKey != null && !this.secretKey.isEmpty() &&
                    this.bucketName != null && !this.bucketName.isEmpty()) {
                try {
                    this.getBucketManager().getBucketInfo(this.bucketName);
                } catch (QiniuException e) {
                    return e;
                }
            }
            return null;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.QiniuStore_DescriptorImpl_DisplayName();
        }

        public String getAccessKey() {
            return this.accessKey;
        }

        public String getSecretKey() {
            return this.secretKey;
        }

        public String getBucketName() {
            return this.bucketName;
        }

        public String getObjectNamePrefix() {
            return this.objectNamePrefix;
        }

        public String getDownloadDomain() {
            return this.downloadDomain;
        }

        public boolean isUseHTTPs() {
            return this.useHTTPs;
        }

        @Nonnull
        private BucketManager getBucketManager() {
            return new BucketManager(this.getAuth(), this.getConfiguration());
        }

        @Nonnull
        private Auth getAuth() {
            return Auth.create(this.accessKey, this.secretKey);
        }

        @Nonnull
        private Configuration getConfiguration() {
            if (this.rsDomain != null && !this.rsDomain.isEmpty() && !Configuration.defaultRsHost.equals(this.rsDomain)) {
                Configuration.defaultRsHost = this.rsDomain;
            }
            if (this.ucDomain != null && !this.ucDomain.isEmpty() && !Configuration.defaultUcHost.equals(this.ucDomain)) {
                Configuration.defaultUcHost = this.ucDomain;
            }
            if (this.apiDomain != null && !this.apiDomain.isEmpty() && !Configuration.defaultApiHost.equals(this.apiDomain)) {
                Configuration.defaultApiHost = this.apiDomain;
            }

            final Configuration config = new Configuration();
            config.useHttpsDomains = this.useHTTPs;
            return config;
        }
    }
}
