package fi.poltsi.vempain.common.api;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
public enum FileTypeEnum {

	ARCHIVE("(Un)Compressed archive files", "archive"),
	AUDIO("Audio files", "audio"),
	BINARY("Binary file", "binary"),
	MUSIC("Music files with artist/album/track metadata", "music"),
	DATA("Various data files, binary or ascii", "data"),
	DOCUMENT("Document files", "document"),
	EXECUTABLE("Executable files including scripts", "executable"),
	FONT("Font files", "font"),
	ICON("Icon files", "icon"),
	IMAGE("Bitmap image files", "image"),
	INTERACTIVE("Interactive files (Flash, Shockwave etc)", "interactive"),
	THUMB("Thumb file", "thumb"),
	UNKNOWN("Unknown filetype", "unknown"),
	VECTOR("Vector image files", "vector"),
	VIDEO("Video files", "video");

	private static final Map<String, FileTypeEnum> BY_DESCRIPTION = new HashMap<>();
	private static final Map<String, FileTypeEnum> BY_NAME        = new HashMap<>();
	// Explicit mimetype -> class mapping for common types that don't map cleanly by top-level type
	private static final Map<String, FileTypeEnum> BY_MIMETYPE    = new HashMap<>();

	static {
		for (FileTypeEnum fcm : values()) {
			BY_DESCRIPTION.put(fcm.description, fcm);
			BY_NAME.put(fcm.shortName, fcm);
		}
	}

	// Populate common mimetypes per class
	static {
		// Documents (Office, ODF, PDF, HTML/MD/TXT/RTF)
		registerMime(DOCUMENT,
		             "application/pdf",
		             "application/msword",
		             "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
		             "application/vnd.ms-excel",
		             "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
		             "application/vnd.ms-powerpoint",
		             "application/vnd.openxmlformats-officedocument.presentationml.presentation",
		             "application/rtf",
		             "application/epub+zip",
		             "application/vnd.oasis.opendocument.text",
		             "application/vnd.oasis.opendocument.spreadsheet",
		             "application/vnd.oasis.opendocument.presentation",
		             "text/plain",
		             "text/markdown",
		             "text/html",
		             "text/rtf"
		);

		// Archives and compressed
		registerMime(ARCHIVE,
		             "application/zip",
		             "application/gzip",
		             "application/x-bzip2",
		             "application/x-7z-compressed",
		             "application/x-rar-compressed",
		             "application/x-tar",
		             "application/x-xz"
		);

		// Executables, installers, scripts
		registerMime(EXECUTABLE,
		             "application/x-msdownload",              // .exe
		             "application/x-dosexec",
		             "application/x-executable",              // ELF
		             "application/x-sharedlib",
		             "application/x-msi",                     // .msi
		             "application/vnd.android.package-archive", // .apk
		             "application/java-archive",              // .jar
		             "application/x-sh",                      // shell script
		             "text/x-shellscript",                    // shell script
		             "application/x-bat"                      // batch
		);

		// Interactive (Flash/Shockwave etc.)
		registerMime(INTERACTIVE,
		             "application/x-shockwave-flash",         // .swf
		             "application/x-director"                 // Shockwave
		);

		// Data (structured text/binary data formats)
		registerMime(DATA,
		             "application/json",
		             "application/xml",
		             "text/xml",
		             "text/csv",
		             "application/csv",
		             "application/x-ndjson",
		             "application/yaml",
		             "text/yaml",
		             "application/x-yaml",
		             "application/vnd.geo+json"
		);

		// Vector graphics
		registerMime(VECTOR,
		             "image/svg+xml",
		             "application/postscript",                // .ps / .eps
		             "application/eps",
		             "application/x-eps",
		             "application/vnd.adobe.illustrator"      // .ai
		);

		// Icons
		registerMime(ICON,
		             "image/vnd.microsoft.icon",
		             "image/x-icon"                           // .ico
		);

		// Fonts that sometimes appear under application/*
		registerMime(FONT,
		             "application/font-woff",
		             "application/font-woff2",
		             "application/x-font-ttf",
		             "application/x-font-otf"
		);

		// Generic binary
		registerMime(BINARY,
		             "application/octet-stream",
		             "application/x-binary"
		);

		// Thumbnails (non-standard but seen in the wild)
		registerMime(THUMB,
		             "image/x-thumbnail",
		             "application/x-thumbnail"
		);
	}

	public final  String shortName;
	private final String description;

	FileTypeEnum(String description, String shortName) {
		this.description = description;
		this.shortName   = shortName;
	}

	private static void registerMime(FileTypeEnum clazz, String... mimeTypes) {
		for (String m : mimeTypes) {
			if (m != null) {
				BY_MIMETYPE.put(m.toLowerCase(Locale.ROOT), clazz);
			}
		}
	}

	public static FileTypeEnum getFileTypeByMimetype(String mimetype) {
		if (mimetype == null || mimetype.isBlank()) {
			return UNKNOWN;
		}
		final String mt = mimetype.trim()
		                          .toLowerCase(Locale.ROOT);

		// 1) Explicit known mimetypes
		FileTypeEnum mapped = BY_MIMETYPE.get(mt);
		if (mapped != null) {
			return mapped;
		}

		// 2) Top-level direct mappings
		var type = mt.split("/")[0];
		if (type.equals("image") ||
		    type.equals("audio") ||
		    type.equals("video") ||
		    type.equals("font")) {
			return BY_NAME.get(type);
		}

		// 3) Text defaults to document unless explicitly overridden (e.g., shellscript handled above)
		if (type.equals("text")) {
			return DOCUMENT;
		}

		// 4) Remaining common families
		if (mt.contains("application/vnd.ms-") ||
		    mt.contains("application/vnd.openxmlformats-officedocument.") ||
		    mt.contains("application/vnd.oasis.opendocument")) {
			return DOCUMENT;
		}

		if (mt.equals("application/gzip") ||
		    mt.equals("application/x-bzip2") ||
		    mt.equals("application/zip") ||
		    mt.equals("application/x-7z-compressed") ||
		    mt.equals("application/x-rar-compressed") ||
		    mt.equals("application/x-tar") ||
		    mt.equals("application/x-xz")) {
			return ARCHIVE;
		}

		return UNKNOWN;
	}

	public static String getFileTypeNameByMimetype(String mimetype) {
		return getFileTypeByMimetype(mimetype).shortName;
	}

	public static Set<String> getFileTypeNames() {
		return BY_NAME.keySet();
	}
}
