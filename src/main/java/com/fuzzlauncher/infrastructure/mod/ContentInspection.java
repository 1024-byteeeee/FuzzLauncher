package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.content.ContentFile;

import java.util.List;

public record ContentInspection(boolean readable, List<ContentFile.Metadata> metadata, String problem, byte[] icon) {
    static final ContentInspection WITHOUT_METADATA = new ContentInspection(true, List.of(), "", null);
}
