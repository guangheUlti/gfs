package com.guanghe.fs.file.domain.event;

import com.guanghe.fs.file.domain.dto.CreateFileShareAccessRecordCmd;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.io.Serial;

@Getter
public class CreateFileShareAccessRecordEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CreateFileShareAccessRecordCmd cmd;

    public CreateFileShareAccessRecordEvent(Object source, CreateFileShareAccessRecordCmd cmd) {
        super(source);
        this.cmd = cmd;
    }
}
