package io.jenkins.plugins;

import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;
import hudson.Extension;
import hudson.model.Run;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public class QiniuArtifactManagerFactory extends ArtifactManagerFactory {
    private static final Logger LOG = Logger.getLogger(QiniuArtifactManagerFactory.class.getName());
    private final QiniuConfig config;

    @DataBoundConstructor
    public QiniuArtifactManagerFactory(@Nonnull final String accessKey, @Nonnull final Secret secretKey, @Nonnull final String bucketName,
                                       @Nonnull final String objectNamePrefix, @Nonnull final String downloadDomain,
                                       @Nonnull final String rsDomain, @Nonnull final String ucDomain, @Nonnull final String apiDomain,
                                       final boolean useHTTPs, final boolean infrequentStorage) {
        if (accessKey.isEmpty()) {
            throw new IllegalArgumentException("accessKey must not be null or empty");
        } else if (secretKey.getPlainText().isEmpty()) {
            throw new IllegalArgumentException("secretKey must not be null or empty");
        } else if (bucketName.isEmpty()) {
            throw new IllegalArgumentException("bucketName must not be null or empty");
        }
        final QiniuConfig config = new QiniuConfig(accessKey, secretKey, bucketName, objectNamePrefix, downloadDomain,
                rsDomain, ucDomain, apiDomain, useHTTPs, infrequentStorage);
        if (downloadDomain.isEmpty()) {
            try {
                final String[] domainList = config.getBucketManager().domainList(config.getBucketName());
                if (domainList.length > 0) {
                    this.config = new QiniuConfig(accessKey, secretKey, bucketName, objectNamePrefix, domainList[domainList.length - 1],
                            rsDomain, ucDomain, apiDomain, useHTTPs, infrequentStorage);
                } else {
                    throw new CannotGetDownloadDomain("Bucket " + config.getBucketName() + " are not bound with any download domain");
                }
            } catch (QiniuException e) {
                throw new QiniuRuntimeException(e);
            }
        } else {
            this.config = config;
        }
        LOG.log(Level.INFO, "QiniuArtifactManagerFactory is configured: accessKey={0}, bucketName={1}, downloadDomain={2}",
                new Object[]{this.config.getAccessKey(), this.config.getBucketName(), this.config.getDownloadDomain()});
    }

    @Nonnull
    @Override
    public ArtifactManager managerFor(Run<?, ?> run) {
        LOG.log(Level.INFO, "QiniuArtifactManagerFactory creates QiniuArtifactManager");
        return new QiniuArtifactManager(run, this.config);
    }

    @CheckForNull
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) Jenkins.get().getDescriptor(this.getClass());
    }

    @Extension
    public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {
        @Nullable
        private String accessKey, bucketName, downloadDomain;
        @Nullable
        private Secret secretKey;
        @Nonnull
        private String rsDomain = Configuration.defaultRsHost,
                ucDomain = Configuration.defaultUcHost,
                apiDomain = Configuration.defaultApiHost;
        private boolean useHTTPs = false;

        public DescriptorImpl() {
            load();
            LOG.log(Level.INFO, "Load QiniuArtifactManagerFactory.DescriptorImpl, accessKey={0}, rsDomain={1}", new Object[]{this.accessKey, this.rsDomain});
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.QiniuArtifactManagerFactory_DescriptorImpl_DisplayName();
        }

        public FormValidation doCheckAccessKey(@QueryParameter final String accessKey) throws IOException, ServletException {
            if (accessKey.isEmpty()) {
                return FormValidation.error(Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_accessKeyIsEmpty());
            }
            this.accessKey = accessKey;
            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err == null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName());
            }
        }

        public FormValidation doCheckSecretKey(@QueryParameter Secret secretKey) throws IOException, ServletException {
            if (secretKey.getPlainText().isEmpty()) {
                return FormValidation.error(Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_secretKeyIsEmpty());
            }
            this.secretKey = secretKey;
            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err == null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName());
            }
        }

        public FormValidation doCheckBucketName(@QueryParameter final String bucketName) throws IOException, ServletException {
            if (bucketName.isEmpty()) {
                return FormValidation.error(Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_bucketNameIsEmpty());
            }
            this.bucketName = bucketName;
            final Throwable err = this.checkAccessKeySecretKeyAndBucketName();
            if (err == null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidAccessKeySecretKeyAndBucketName());
            }
        }

        public FormValidation doCheckDownloadDomain(@QueryParameter final String downloadDomain) throws IOException, ServletException {
            if (!downloadDomain.isEmpty()) {
                try {
                    new URL("http://" + downloadDomain).openConnection().connect();
                } catch (Exception err) {
                    return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidDownloadDomain());
                }
            }
            this.downloadDomain = downloadDomain;

            if (!this.checkDownloadDomain()) {
                return FormValidation.error(Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_downloadDomainIsEmpty());
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckRsDomain(@QueryParameter final String rsDomain) throws IOException, ServletException {
            if (!rsDomain.isEmpty()) {
                try {
                    new URL("http://" + rsDomain).openConnection().connect();
                } catch (Exception err) {
                    return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidRsDomain());
                }
            }
            this.rsDomain = rsDomain;
            return FormValidation.ok();
        }

        public FormValidation doCheckUcDomain(@QueryParameter final String ucDomain) throws IOException, ServletException {
            if (!ucDomain.isEmpty()) {
                try {
                    new URL("http://" + ucDomain).openConnection().connect();
                } catch (Exception err) {
                    return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidUcDomain());
                }
            }
            this.ucDomain = ucDomain;
            return FormValidation.ok();
        }

        public FormValidation doCheckApiDomain(@QueryParameter final String apiDomain) throws IOException, ServletException {
            if (!apiDomain.isEmpty()) {
                try {
                    new URL("http://" + apiDomain).openConnection().connect();
                } catch (Exception err) {
                    return FormValidation.error(err, Messages.QiniuArtifactManagerFactory_DescriptorImpl_errors_invalidAPIDomain());
                }
            }
            this.apiDomain = apiDomain;
            return FormValidation.ok();
        }

        private Throwable checkAccessKeySecretKeyAndBucketName() {
            final BucketManager bucketManager = this.getBucketManager();
            if (bucketManager != null &&
                    this.accessKey != null && !this.accessKey.isEmpty() &&
                    this.secretKey != null && !this.secretKey.getPlainText().isEmpty() &&
                    this.bucketName != null && !this.bucketName.isEmpty()) {
                try {
                    bucketManager.getBucketInfo(this.bucketName);
                } catch (QiniuException e) {
                    return e;
                }
            }
            return null;
        }

        private boolean checkDownloadDomain() {
            final BucketManager bucketManager = this.getBucketManager();
            if (bucketManager != null &&
                    this.accessKey != null && !this.accessKey.isEmpty() &&
                    this.secretKey != null && !this.secretKey.getPlainText().isEmpty() &&
                    this.bucketName != null && !this.bucketName.isEmpty() &&
                    (this.downloadDomain == null || this.downloadDomain.isEmpty())) {
                try {
                    final String[] domainList = bucketManager.domainList(this.bucketName);
                    return domainList.length > 0;
                } catch (QiniuException e) {
                    return false;
                }
            }
            return true;
        }

        @CheckForNull
        private BucketManager getBucketManager() {
            final Auth auth = this.getAuth();
            final Configuration config = this.getConfiguration();
            if (auth == null) {
                return null;
            }
            return new BucketManager(auth, config);
        }

        @CheckForNull
        private Auth getAuth() {
            if (this.accessKey == null || this.accessKey.isEmpty() ||
                    this.secretKey == null || this.secretKey.getPlainText().isEmpty()) {
                return null;
            }
            return Auth.create(this.accessKey, this.secretKey.getPlainText());
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

    public static class QiniuRuntimeException extends RuntimeException {
        public QiniuRuntimeException(final Throwable cause) {
            super(cause);
        }
    }

    public static class CannotGetDownloadDomain extends IllegalArgumentException {
        public CannotGetDownloadDomain(final String s) {
            super(s);
        }
    }

    @Nonnull
    public BucketManager getBucketManager() {
        return this.config.getBucketManager();
    }

    @Nonnull
    public Auth getAuth() {
        return this.config.getAuth();
    }

    @Nonnull
    public Configuration getConfiguration() {
        return this.config.getConfiguration();
    }

    @Nonnull
    public String getAccessKey() {
        return this.config.getAccessKey();
    }

    @Nonnull
    public Secret getSecretKey() {
        return this.config.getSecretKey();
    }

    @Nonnull
    public String getBucketName() {
        return this.config.getBucketName();
    }

    @Nonnull
    public String getObjectNamePrefix() {
        return this.config.getObjectNamePrefix();
    }

    @Nonnull
    public String getDownloadDomain() {
        return this.config.getDownloadDomain();
    }

    @Nonnull
    public String getRsDomain() {
        return this.config.getRsDomain();
    }

    @Nonnull
    public String getUcDomain() {
        return this.config.getUcDomain();
    }

    @Nonnull
    public String getApiDomain() {
        return this.config.getApiDomain();
    }

    public boolean isUseHTTPs() {
        return this.config.isUseHTTPs();
    }

    public boolean isInfrequentStorage() {
        return this.config.isInfrequentStorage();
    }
}
