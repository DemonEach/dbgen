package ru.demoneach.dbgenerator.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Rule {
    private RuleType ruleType;
    private List<String> value;
}
