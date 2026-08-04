package com.company.liquibasevalidator.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatchFilterTest {

    private val diff = """
        diff --git a/database/countries/SG/staticdatasetup/account_type.sql b/database/countries/SG/staticdatasetup/account_type.sql
        index 1234567..89abcde 100644
        --- a/database/countries/SG/staticdatasetup/account_type.sql
        +++ b/database/countries/SG/staticdatasetup/account_type.sql
        @@ -20,4 +20,6 @@ CREATE GLOBAL TEMPORARY TABLE tmp_account_type_sg (
             code        VARCHAR2(50 CHAR)  NOT NULL,
        -    name        VARCHAR2(100 CHAR) NOT NULL,
        +    name        VARCHAR2(255 CHAR) NOT NULL,
        +    remarks     VARCHAR2(20 CHAR),
             description VARCHAR2(255 CHAR),
        @@ -30,2 +32,3 @@ INSERT INTO tmp_account_type_sg (code, name)
         VALUES ('SAVINGS', 'a');
        +INSERT INTO tmp_account_type_sg (code, name) VALUES ('SAVINGS', 'b');
        diff --git a/old.sql b/old.sql
        deleted file mode 100644
        --- a/old.sql
        +++ /dev/null
        @@ -1,3 +0,0 @@
        -gone
        -gone
        -gone
    """.trimIndent()

    private val filter = PatchFilter.parse(diff)

    @Test
    fun `changed new-file lines are extracted per hunk`() {
        val lines = filter.changedLinesFor(
            "C:\\repo\\database\\countries\\SG\\staticdatasetup\\account_type.sql",
        )
        // hunk 1 starts at new line 20: line 20 context, 21 (+name), 22 (+remarks)
        // hunk 2 starts at new line 32: 32 context, 33 (+INSERT)
        assertEquals(setOf(21, 22, 33), lines)
    }

    @Test
    fun `suffix matching works across path separators and case`() {
        assertTrue(filter.touches("/home/ci/work/database/countries/SG/staticdatasetup/ACCOUNT_TYPE.SQL"))
        assertFalse(filter.touches("/home/ci/work/database/countries/IN/staticdatasetup/account_type.sql"))
    }

    @Test
    fun `deleted files are ignored`() {
        assertNull(filter.changedLinesFor("/repo/old.sql"))
        assertEquals(1, filter.fileCount)
    }

    @Test
    fun `empty diff touches nothing`() {
        val empty = PatchFilter.parse("")
        assertEquals(0, empty.fileCount)
        assertNull(empty.changedLinesFor("/any/file.sql"))
    }
}
