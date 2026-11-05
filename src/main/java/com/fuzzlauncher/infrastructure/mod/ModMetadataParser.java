package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.content.ContentFile;

import java.io.IOException;
import java.util.List;

/** One metadata format; archive limits and image decoding stay with the reader. */
interface ModMetadataParser {
    String entry();

    void parse(String text, String jarVersion, List<ContentFile.Metadata> output, List<String> icons) throws IOException;
}
