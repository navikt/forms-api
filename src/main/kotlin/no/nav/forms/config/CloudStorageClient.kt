package no.nav.forms.config

import com.google.cloud.NoCredentials
import com.google.cloud.storage.BucketInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import no.nav.forms.config.embedded.GoogleCloudStorage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Scope

@Configuration
@EnableConfigurationProperties(StaticPdfConfig::class)
class CloudStorageClient {

	@Bean
	@Profile("!(local | test | docker)")
	@Qualifier("cloudStorageClient")
	@Scope("prototype")
	fun gcpClient(): Storage = StorageOptions.getDefaultInstance().service

	@Bean
	@Profile("test | local")
	@Qualifier("cloudStorageClient")
	@Scope("prototype")
	fun embeddedClient(cloudStorageConfig: StaticPdfConfig, gcsContainer: GoogleCloudStorage.Container): Storage {
		val host = gcsContainer.getUrl() // From testcontainers
		return buildStorageForTest(host, cloudStorageConfig.bucketName)
	}

	@Bean
	@Profile("docker")
	@Qualifier("cloudStorageClient")
	@Scope("prototype")
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
