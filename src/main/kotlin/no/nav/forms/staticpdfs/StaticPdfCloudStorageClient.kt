package no.nav.forms.staticpdfs

import no.nav.forms.config.StaticPdfConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import com.google.cloud.NoCredentials
import com.google.cloud.storage.BucketInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import no.nav.forms.config.embedded.GoogleCloudStorage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@Configuration
@EnableConfigurationProperties(StaticPdfConfig::class)
class StaticPdfCloudStorageClient {

	@Bean
	@Profile("!(local | test | docker)")
	@Qualifier("staticPdfCloudStorageClient")
	fun gcpClient(): Storage = StorageOptions.getDefaultInstance().service

	@Bean
	@Profile("test | local")
	@Qualifier("staticPdfCloudStorageClient")
	fun embeddedClient(cloudStorageConfig: StaticPdfConfig, gcsContainer: GoogleCloudStorage.Container): Storage {
		val host = gcsContainer.getUrl() // From testcontainers
		return buildStorageForTest(host, cloudStorageConfig.bucketName)
	}

	@Bean
	@Profile("docker")
	@Qualifier("staticPdfCloudStorageClient")
	fun dockerClient(cloudStorageConfig: StaticPdfConfig): Storage {
		val host = "http://localhost:5443" // From docker-compose
		return buildStorageForTest(host, cloudStorageConfig.bucketName)
	}

	fun buildStorageForTest(host: String, bucket: String): Storage {
		return StorageOptions
			.newBuilder()
			.setCredentials(NoCredentials.getInstance())
			.setHost(host)
			.setProjectId("formsapi")
			.build()
			.service
			.also {
				if (it.get(bucket) == null) {
					it.create(BucketInfo.of(bucket))
				}
			}
	}
}
