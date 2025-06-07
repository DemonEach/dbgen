package ru.demoneach.dbgenerator.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class DummyObject {
    private Integer id;
    private String name;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
