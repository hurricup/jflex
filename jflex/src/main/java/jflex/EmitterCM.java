package jflex;

import java.io.StringWriter;

/**
 * Based on generatecharacter/GenerateCharacter.java
 * from http://hg.openjdk.java.net tailored to our needs
 *
 * @author gregsh
 */
class EmitterCM {

  static int[] findBestSizes(char[] map, int partitions) {
    int[] curMin = {Integer.MAX_VALUE};
    int[][] minSizes = {null};
    while (partitions > 0) {
      permuteSizesAndFindBest(map, new int[partitions--], curMin, minSizes);
    }
    if (minSizes[0] == null) {
      throw new IllegalArgumentException("best sizes not found");
    }
    return minSizes[0];
  }

  private static void permuteSizesAndFindBest(char[] map, int[] sizes, int[] curMin, int[][] minSizes) {
    int bitsTotal = 0; // 21 for 0x10ffff
    for (int i = map.length; i > 0; i >>= 1) {
      bitsTotal++;
    }

    int last = sizes.length - 1;

    for (int j = last, sum = 0; true ; j = last, sum = 0) {
      for (int i = 0; i <= last; i++) {
        if (sizes[i] == 0 && i != last) { sum = 0; break; }
        sum += sizes[i];
      }
      if (sum == bitsTotal) {
        int newMin = tryGenerateTables(map, sizes);
        //System.out.println(Arrays.toString(sizes) + " -> " + newMin);
        if (curMin[0] > newMin) {
          curMin[0] = newMin;
          minSizes[0] = sizes.clone();
        }
      }

      while (j >= 0 && sizes[j] == bitsTotal) j --;
      if (j < 0) break;

      for (int j0 = j; j0 < last; j0++) {
        sizes[j0 + 1] = 0;
      }
      sizes[j]++;
    }
  }

  private static int tryGenerateTables(char[] map, int[] sizes) {
    try {
      Object[] o = generateForSizes(map, sizes);
      char[][] tables = (char[][])o[0];
      int[] bytes = (int[])o[1];
      return getTotalBytes(tables, sizes, bytes);
    }
    catch (IllegalArgumentException e) {
      return Integer.MAX_VALUE;
    }
  }

  static int getTotalBytes(char[][] tables, int[] sizes, int[] bytes) {
    int totalBytes = 0;
    for (int k = 0; k < sizes.length - 1; k++) {
      totalBytes += tables[k].length * bytes[k] << 1;
    }
    totalBytes += ((((tables[sizes.length - 1].length * 32) + 31) >> 5) << 1);
    return totalBytes;
  }


  private static char[][] buildTable(char[] map, int size) {
    int n = map.length;
    if (((n >> size) << size) != n) {
      throw new IllegalArgumentException("Length " + n + " is not a multiple of " + (1 << size));
    }
    int m = 1 << size;
    // We know the final length of the new map up front.
    char[] newmap = new char[n >> size];
    // The buffer is used temporarily to hold data for the compressed table
    // because we don't know its final length yet.
    char[] buffer = new char[n];
    int ptr = 0;
    OUTER:
    for (int i = 0; i < n; i += m) {
      // For every block of size m in the original map...
      MIDDLE:
      for (int j = 0; j < ptr; j += m) {
        // Find out whether there is already a block just like it in the buffer.
        for (int k = 0; k < m; k++) {
          if (buffer[j + k] != map[i + k])
            continue MIDDLE;
        }
        // There is a block just like it at position j, so just
        // put its index into the new map (thereby sharing it).
        newmap[i >> size] = (char)(j >> size);
        continue OUTER;
      } // end MIDDLE
      // There is no block just like it already, so add it to
      // the buffer and put its index into the new map.
      System.arraycopy(map, i, buffer, ptr, m);
      newmap[i >> size] = (char)(ptr >> size);
      ptr += m;
    } // end OUTER
    // Now we know how char the compressed table should be,
    // so create a new array and copy data from the temporary buffer.
    char[] newdata = new char[ptr];
    System.arraycopy(buffer, 0, newdata, 0, ptr);
    // Return the new map and the new data table.
    return new char[][]{newmap, newdata};
  }


  static Object[] generateForSizes(char[] map, int[] sizes) {
    int sum = 0;
    int[] shifts = new int[sizes.length];
    for (int k = sizes.length - 1; k >= 0; k--) {
      shifts[k] = sum;
      sum += sizes[k];
    }
    if ((1 << sum) < map.length || (1 << (sum - 1)) >= map.length) {
      throw new IllegalArgumentException("Bit field widths total to " + sum +
                               ": wrong total for map of size " + map.length);
    }
    // need a table for each set of lookup bits in char
    char[][] tables = new char[sizes.length][];
    // the last table is the map
    tables[sizes.length - 1] = map;
    for (int j = sizes.length - 1; j > 0; j--) {
      //if (verbose && bins == 0)
      //  System.err.println("Building map " + (j + 1) + " of bit width " + sizes[j]);
      char[][] temp = buildTable(tables[j], sizes[j]);
      tables[j - 1] = temp[0];
      tables[j] = temp[1];
    }
    boolean[] preshifted = new boolean[sizes.length];
    int[] zeroextend = new int[sizes.length];
    int[] bytes = new int[sizes.length];
    for (int j = 0; j < sizes.length - 1; j++) {
      int len = tables[j + 1].length;
      int size = sizes[j + 1];
      if (len > 0x100 && (len >> size) <= 0x100) {
        len >>= size;
        preshifted[j] = false;
      }
      else if (len > 0x10000 && (len >> size) <= 0x10000) {
        len >>= size;
        preshifted[j] = false;
      }
      else {
        preshifted[j] = true;
      }
      if (len > 0x7F && len <= 0xFF) {
      }
      else if (len > 0x7FFF && len <= 0xFFFF) {
        zeroextend[j] = 0xFFFF;
      }
      else {
        zeroextend[j] = 0;
      }
      if (len <= 0x100) bytes[j] = 1;
      else if (len <= 0x10000) bytes[j] = 2;
      else bytes[j] = 4;
    }
    preshifted[sizes.length - 1] = true;
    zeroextend[sizes.length - 1] = 0;
    bytes[sizes.length - 1] = 0;

    return new Object[] { tables, bytes, preshifted, shifts, zeroextend};
  }

  static String genAccess(String tbl, String var, int bits, int[] sizes, int[] shifts, int[] zeroextend, boolean[] preshifted) {
    String access = null;
    int bitoffset = bits == 1 ? 5 : bits == 2 ? 4 : bits == 4 ? 3 : 0;
    for (int k = 0; k < sizes.length; k++) {
      String tableName = "ZZ_CMAP_" + String.valueOf((char) ('Z' - k));
      int offset = ((k < sizes.length - 1) ? 0 : bitoffset);
      int shift = shifts[k] + offset;
      String shifted = (shift == 0) ? var : "(" + var + ">>" + shift + ")";
      int mask = (1 << (sizes[k] - offset)) - 1;
      String masked = (k == 0) ? shifted :
              "(" + shifted + "&0x" + Integer.toHexString(mask) + ")";
      String index = (k == 0) ? masked :
              (mask == 0) ? access : "(" + access + "|" + masked + ")";
      String indexNoParens = (index.charAt(0) != '(') ? index :
              index.substring(1, index.length() - 1);
      String tblname = (k == sizes.length - 1) ? tbl : tableName;
      String fetched = tblname + "[" + indexNoParens + "]";
      String zeroextended = (zeroextend[k] == 0) ? fetched :
              "(" + fetched + "&0x" + Integer.toHexString(zeroextend[k]) + ")";
      int adjustment = preshifted[k] ? 0 :
              sizes[k + 1] - ((k == sizes.length - 2) ? bitoffset : 0);
      String adjusted = (preshifted[k] || adjustment == 0) ? zeroextended :
              "(" + zeroextended + "<<" + adjustment + ")";
      String bitshift = (bits == 1) ? "(" + var + "&0x1F)" :
              (bits == 2) ? "((" + var + "&0xF)<<1)" :
                      (bits == 4) ? "((" + var + "&7)<<2)" : null;
      String extracted = ((k < sizes.length - 1) || (bits >= 8)) ? adjusted :
              "((" + adjusted + ">>" + bitshift + ")&" +
                      (bits == 4 ? "0xF" : String.valueOf((1 << bits) - 1)) + ")";
      access = extracted;
    }
    return access;
  }


  static String genTables(char[][] tables, int[] sizes, int[] bytes, boolean[] preshifted) {
    StringBuffer result = new StringBuffer();

    for (int k = 0; k < sizes.length - 1; k++) {
      String tableName = "ZZ_CMAP_" + String.valueOf((char)('Z' - k));
      genTable(result, tableName, tables[k], 0, bytes[k] << 3, preshifted[k], sizes[k + 1]);
      result.append("\n");
    }
    genTable(result, "ZZ_CMAP_" + "A", tables[sizes.length - 1], 0, 16, false, 0);

    return result.toString();
  }

  private static void genTable(StringBuffer result, String name,
                               char[] table, int extract, int bits,
                               boolean preshifted, int shift) {

    String atype = "char";
    int entriesPerChar = 1;
    boolean noConversion = atype.equals("char");
    boolean shiftEntries = preshifted && shift != 0;

    result.append("  /*");
    result.append(" The ").append(name).append(" table has ").append(table.length).append(" entries */\n");
    result.append("  static final ");
    result.append(atype);
    result.append(" ").append(name).append("[");
    result.append("] = zzUnpackCMap(\n");
    StringWriter theString = new MyStringWriter();
    int entriesInCharSoFar = 0;
    char ch = '\u0000';
    for (int j = 0; j < table.length; ++j) {
      int entry = table[j] >> extract;
      if (shiftEntries) entry <<= shift;
      if (entry >= (1L << bits)) {
        throw new IllegalArgumentException("Entry too big");
      }
      // Pack multiple entries into a character
      ch = (char) (((int) ch >> bits) | (entry << (entriesPerChar - 1) * bits));
      ++entriesInCharSoFar;
      if (entriesInCharSoFar == entriesPerChar) {
        // Character is full
        theString.append(ch);
        entriesInCharSoFar = 0;
        ch = '\u0000';
      }
    }
    if (entriesInCharSoFar > 0) {
      while (entriesInCharSoFar < entriesPerChar) {
        ch = (char) ((int) ch >> bits);
        ++entriesInCharSoFar;
      }
      theString.append(ch);
    }
    theString.flush();
    result.append(theString.getBuffer());
    if (noConversion) {
      result.append(")");
    }
    result.append(";\n");
  }

  private static class MyStringWriter extends StringWriter {
    char cur;
    int count;

    @Override
    public StringWriter append(char c) {
      if (cur == c && count <= 0xffff) {
        count++;
      }
      else {
        if (count > 0) {
          appendImpl((char) count);
          appendImpl(cur);
        }
        cur = c;
        count = 1;
      }
      return this;
    }

    @Override
    public void flush() {
      if (count > 0) {
        appendImpl((char) count);
        appendImpl(cur);
        count = 0;
      }
      if (limit > 0) {
        super.append('"');
        limit = 0;
      }
    }

    int limit;

    void appendImpl(char c) {
      if (getBuffer().length() >= limit) {
        if (limit > 0) super.append("\"+\n");
        limit = getBuffer().length() + 78;
        super.append("    \"");
      }
      if (c > 255) {
        super.append("\\u");
        if (c < 0x1000) super.append("0");
        super.append(Integer.toHexString(c));
      }
      else {
        super.append("\\");
        super.append(Integer.toOctalString(c));
      }
    }
  }
}
