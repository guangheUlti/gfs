package com.guanghe.fs.file.domain.vo;

import com.guanghe.fs.file.domain.FileCollection;
import com.guanghe.fs.file.domain.FileCollectionSubmission;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileCollectionUploadContext {
    private FileCollection collection;
    private FileCollectionSubmission submission;
}
