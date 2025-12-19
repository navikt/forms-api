package no.nav.forms.staticpdfs

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import no.nav.forms.config.StaticPdfConfig
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.utils.PdfLanguageCode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.ByteBuffer
import java.time.OffsetDateTime

@Service
public class StaticPdfService(
	private val staticPdfConfig: StaticPdfConfig,
	@field:Qualifier("cloudStorageClient") private val storage: Storage,
) {

	fun save(file: MultipartFile, formPath: String, language: PdfLanguageCode, userId: String): StaticPdfMetadata {
		val fileName = file.originalFilename ?: file.name
		val fileContent = file.resource.contentAsByteArray
		val fileSize = fileContent.size

		val blobName = "$formPath/${language.value}"
		val createdAt = OffsetDateTime.now()
		val blobInfo = BlobInfo
			.newBuilder(BlobId.of(staticPdfConfig.bucketName, blobName))
			.setMetadata(
				mapOf(
					"formPath" to formPath,
					"fileName" to fileName,
					"fileSize" to fileSize.toString(),
					"createdAt" to createdAt.toString(),
					"createdBy" to userId,
					"language" to language.value,
				)
			)
			.build()
		storage.writer(blobInfo).use {
			it.write(ByteBuffer.wrap(fileContent, 0, fileSize))
		}
		return StaticPdfMetadata(
			formPath = formPath,
			fileName = fileName,
			fileSize = fileSize.toString(),
			language = language,
			createdBy = userId,
			createdAt = createdAt,
		)
	}

	fun getAll(formPath: String): List<StaticPdfMetadata> {
		val blobs = storage.list(staticPdfConfig.bucketName, Storage.BlobListOption.prefix(formPath))
		return blobs.iterateAll().map { blob ->
			val createdAtString = blob.metadata?.get("createdAt")
			StaticPdfMetadata(
				formPath = blob.metadata?.get("formPath") ?: "-",
				fileName = blob.metadata?.get("fileName") ?: "-",
				fileSize = blob.metadata?.get("fileSize") ?: "-",
				language = PdfLanguageCode.validate(blob.metadata?.get("language") ?: "-"),
				createdBy = blob.metadata?.get("createdBy") ?: "-",
				createdAt = if (createdAtString != null) OffsetDateTime.parse(createdAtString) else OffsetDateTime.MIN,
			)
		}
	}

	fun delete(formPath: String, language: PdfLanguageCode) {
		val blobName = "$formPath/${language.value}"
		val blob = storage.get(staticPdfConfig.bucketName, blobName)
			?: throw ResourceNotFoundException("Static PDF not found", blobName)
		if (!storage.delete(blob.blobId)) {
			throw RuntimeException("Failed to delete static PDF with blob name: $blobName")
		}
	}

	fun getContent(formPath: String, language: PdfLanguageCode): Pair<Resource, String> {
		val blobName = "$formPath/${language.value}"
		val blob = storage.get(staticPdfConfig.bucketName, blobName)
			?: throw ResourceNotFoundException("Static PDF not found", blobName)
		val innhold = storage.readAllBytes(blob.blobId)
		val fileName = blob.metadata?.get("fileName") ?: "$formPath-${language.value}.pdf"
		return Pair(org.springframework.core.io.ByteArrayResource(innhold), fileName)
	}

}
