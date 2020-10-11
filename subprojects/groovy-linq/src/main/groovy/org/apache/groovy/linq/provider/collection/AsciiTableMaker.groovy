/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.groovy.linq.provider.collection


import java.util.stream.IntStream

/**
 * Make ascii table
 *
 * @since 4.0.0
 */
class AsciiTableMaker {
    private static final int DEFAULT_MAX_WIDTH = 64

    /**
     * make ascii table for list whose elements are of type {@link NamedTuple}
     *
     * @param tableData
     * @return ascii table
     */
    static <T> String makeAsciiTable(List<T> tableData) {
        if (!tableData.isEmpty()) {
            List<String[]> list = new ArrayList<>(tableData.size() + 1)
            def firstRecord = tableData.get(0)
            if (firstRecord instanceof NamedTuple) {
                list.add(firstRecord.nameList as String[])
                tableData.stream().forEach(e -> {
                    if (e instanceof NamedTuple) {
                        list.add(e.stream().map(c -> c?.toString()).toArray() as String[])
                    }
                })

                return makeAsciiTable(list, DEFAULT_MAX_WIDTH, true)
            }
        }

        return tableData.toString()
    }

    /**
     * Create a ascii table
     *
     * @param table table data
     * @param maxWidth Maximum allowed width. Line will be wrapped beyond this width.
     * @param leftJustifiedRows If true, it will add "-" as a flag to format string to make it left justified. Otherwise right justified.
     */
    static String makeAsciiTable(List<String[]> table, int maxWidth, boolean leftJustifiedRows) {
        StringBuilder result = new StringBuilder()

        if (0 == maxWidth) maxWidth = DEFAULT_MAX_WIDTH

        // Create new table array with wrapped rows
        List<String[]> tableList = new ArrayList<>(table)
        List<String[]> finalTableList = new ArrayList<>()
        for (String[] row : tableList) {
            // If any cell data is more than max width, then it will need extra row.
            boolean needExtraRow = false
            // Count of extra split row.
            int splitRow = 0
            do {
                needExtraRow = false
                String[] newRow = new String[row.length]
                for (int i = 0; i < row.length; i++) {
                    // If data is less than max width, use that as it is.
                    def col = row[i] ?: ''
                    if (col.length() < maxWidth) {
                        newRow[i] = splitRow == 0 ? col : ""
                    } else if ((col.length() > (splitRow * maxWidth))) {
                        // If data is more than max width, then crop data at maxwidth.
                        // Remaining cropped data will be part of next row.
                        int end = Math.min(col.length(), ((splitRow * maxWidth) + maxWidth))
                        newRow[i] = col.substring((splitRow * maxWidth), end)
                        needExtraRow = true
                    } else {
                        newRow[i] = ""
                    }
                }
                finalTableList.add(newRow)
                if (needExtraRow) {
                    splitRow++
                }
            } while (needExtraRow)
        }
        String[][] finalTable = new String[finalTableList.size()][finalTableList.get(0).length]
        for (int i = 0; i < finalTable.length; i++) {
            finalTable[i] = finalTableList.get(i)
        }

        // Calculate appropriate Length of each column by looking at width of data in each column.
        // Map columnLengths is <column_number, column_length>
        Map<Integer, Integer> columnLengths = new HashMap<>()
        Arrays.stream(finalTable).forEach(a -> IntStream.range(0, a.length).forEach(i -> {
            columnLengths.putIfAbsent(i, 0)
            if (columnLengths.get(i) < a[i].length()) {
                columnLengths.put(i, a[i].length())
            }
        }))

        // Prepare format String
        final StringBuilder formatString = new StringBuilder("")
        String flag = leftJustifiedRows ? "-" : ""
        columnLengths.entrySet().stream().forEach(e -> formatString.append("| %" + flag + e.getValue() + "s "))
        formatString.append("|\n")

        // Prepare line for top, bottom & below header row.
        String line = columnLengths.entrySet().stream().reduce("", (ln, b) -> {
            String templn = "+-"
            templn = templn + IntStream.range(0, b.getValue()).boxed().reduce("", (ln1, b1) -> ln1 + "-",
                    (a1, b1) -> a1 + b1)
            templn = templn + "-"
            return ln + templn
        }, (a, b) -> a + b)
        line = line + "+\n"

        // Print table
        result.append(line)
        Arrays.stream(finalTable)
                .limit(1)
                .forEach(a -> result.append(String.format(formatString.toString(), (Object[]) a)))
        result.append(line)

        IntStream.range(1, finalTable.length)
                .forEach(a -> result.append(String.format(formatString.toString(), (Object[]) finalTable[a])))
        result.append(line)

        return result.toString()
    }

    private AsciiTableMaker() {}
}
