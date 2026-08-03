package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.sql.TypeKind

/**
 * Validates a staging (temp) table definition against the target table(s) it feeds:
 * declared datatype/length compatibility, declared nullability, unused staging columns.
 */
internal class TempTableValidator(
    private val options: ValidationOptions,
    private val sink: ProblemSink,
) {

    fun validate(flow: StagingFlow) {
        val temp = flow.tempTable
        for (column in temp.columns) {
            for ((mapping, targetColName) in flow.targetsOf(column.nameLower)) {
                val targetSchema = mapping.targetSchema ?: continue
                val targetColumn = targetSchema.findColumn(targetColName) ?: continue
                val targetDisplay = "${targetSchema.name}.${targetColumn.name}"

                TypeCompatibility.check(column.dataType, targetColumn.dataType)?.let { issue ->
                    val fixes = mutableListOf<QuickFixData>()
                    if (issue.severity == Severity.ERROR && targetColumn.dataType.kind != TypeKind.OTHER) {
                        fixes += QuickFixData.ReplaceText(
                            range = column.typeRange,
                            newText = targetColumn.dataType.display(),
                            name = "Change ${column.dataType.display()} to ${targetColumn.dataType.display()}",
                        )
                    }
                    sink.add(
                        issue.severity, issue.category,
                        "Liquibase: temporary column '${column.name}' in '${temp.tableName}' is " +
                            "${column.dataType.display()} but target column '$targetDisplay' is " +
                            "${targetColumn.dataType.display()}: ${issue.detail}",
                        column.typeRange,
                        fixes,
                    )
                }

                if (options.validateNullConstraints && !column.notNull && !targetColumn.nullable) {
                    sink.add(
                        Severity.WEAK_WARNING, ProblemCategory.NULLABILITY,
                        "Liquibase: staging column '${temp.tableName}.${column.name}' allows NULL but " +
                            "target column '$targetDisplay' is NOT NULL",
                        column.nameRange,
                    )
                }
            }
        }

        if (options.warnUnusedStagingColumns) {
            val realMappings = flow.mappings.filter { !it.heuristic }
            if (realMappings.isNotEmpty()) {
                val used = realMappings.flatMap { it.usedSourceColumns }.toSet()
                for (column in temp.columns.filter { it.nameLower !in used }) {
                    sink.add(
                        Severity.WARNING, ProblemCategory.UNUSED,
                        "Liquibase: column '${column.name}' is defined in staging table '${temp.tableName}' " +
                            "but is not used by the MERGE statement",
                        column.nameRange,
                    )
                }
            }
        }
    }
}
