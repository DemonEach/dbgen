package ru.demoneach.dbgenerator.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@ToString
public class Parameters {
    private boolean debug = false;
    private Integer batch = 1;
    private Integer batchSave = 10;
    private Strategy strategy = Strategy.DEFAULT;
    // список таблиц со схемами в формате schema.table
    private List<String> tablesToGenerate;
    private ConnectionParameters connectionParameters;
    private Integer amountOfEntries = 1000;
    private Map<String, String> customTableLinks;
    private Map<String, Rule> fieldGenerationRules;
}
