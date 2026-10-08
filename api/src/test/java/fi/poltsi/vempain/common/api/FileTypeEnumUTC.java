package fi.poltsi.vempain.common.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeEnumUTC {

	@ParameterizedTest
	@CsvSource({
			"application/pdf, DOCUMENT",
			"text/plain, DOCUMENT",
			"text/x-shellscript, EXECUTABLE",
			"application/zip, ARCHIVE",
			"application/json, DATA",
			"image/svg+xml, VECTOR",
			"image/x-icon, ICON",
			"image/jpeg, IMAGE",
			"audio/mpeg, AUDIO",
			"video/mp4, VIDEO",
			"font/woff2, FONT",
			"application/font-woff, FONT",
			"application/octet-stream, BINARY",
			"application/x-shockwave-flash, INTERACTIVE",
			"image/x-thumbnail, THUMB",
			"application/vnd.ms-excel, DOCUMENT",
			"application/x-made-up, UNKNOWN",
	})
	void mapsMimetypesToFileTypes(String mimetype, FileTypeEnum expected) {
		assertThat(FileTypeEnum.getFileTypeByMimetype(mimetype)).isEqualTo(expected);
	}

	@Test
	void blankOrNullMimetypeIsUnknown() {
		assertThat(FileTypeEnum.getFileTypeByMimetype(null)).isEqualTo(FileTypeEnum.UNKNOWN);
		assertThat(FileTypeEnum.getFileTypeByMimetype("  ")).isEqualTo(FileTypeEnum.UNKNOWN);
	}

	@Test
	void mimetypeLookupIgnoresCaseAndSurroundingWhitespace() {
		assertThat(FileTypeEnum.getFileTypeByMimetype(" Application/PDF ")).isEqualTo(FileTypeEnum.DOCUMENT);
		assertThat(FileTypeEnum.getFileTypeNameByMimetype("IMAGE/PNG")).isEqualTo("image");
	}

	@Test
	void everyTypeHasAUniqueShortName() {
		assertThat(FileTypeEnum.getFileTypeNames()).hasSize(FileTypeEnum.values().length)
												 .contains("image", "music", "thumb", "unknown");
	}
}
