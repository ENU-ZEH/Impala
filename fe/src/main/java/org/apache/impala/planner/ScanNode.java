// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.planner;

import java.util.List;

import org.apache.impala.analysis.SlotDescriptor;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.catalog.HdfsFileFormat;
import org.apache.impala.catalog.Table;
import org.apache.impala.catalog.Type;
import org.apache.impala.common.NotImplementedException;
import org.apache.impala.thrift.TNetworkAddress;
import org.apache.impala.thrift.TScanRangeSpec;
import org.apache.impala.thrift.TTableStats;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Representation of the common elements of all scan nodes.
 */
abstract public class ScanNode extends PlanNode {
  protected final TupleDescriptor desc_;

  // Total number of rows this node is expected to process
  protected long inputCardinality_ = -1;

  // Scan-range specs. Populated in init().
  protected TScanRangeSpec scanRangeSpecs_;

  public ScanNode(PlanNodeId id, TupleDescriptor desc, String displayName) {
    super(id, desc.getId().asList(), displayName);
    desc_ = desc;
  }

  public TupleDescriptor getTupleDesc() { return desc_; }

  /**
   * Checks if this scan is supported based on the types of scanned columns and the
   * underlying file formats, in particular, whether complex types are supported.
   *
   * The default implementation throws if this scan would need to materialize a nested
   * field or collection. The scan is ok if the table schema contains complex types, as
   * long as the query does not reference them.
   *
   * Subclasses should override this function as appropriate.
   */
  protected void checkForSupportedFileFormats() throws NotImplementedException {
    Preconditions.checkNotNull(desc_);
    Preconditions.checkNotNull(desc_.getTable());
    for (SlotDescriptor slotDesc: desc_.getSlots()) {
      if (slotDesc.getType().isComplexType() || slotDesc.getColumn() == null) {
        Preconditions.checkNotNull(slotDesc.getPath());
        throw new NotImplementedException(String.format(
            "Scan of table '%s' is not supported because '%s' references a nested " +
            "field/collection.\nComplex types are supported for these file formats: %s.",
            slotDesc.getPath().toString(), desc_.getAlias(),
            Joiner.on(", ").join(HdfsFileFormat.complexTypesFormats())));
      }
    }
  }

  /**
   * Returns all scan range specs.
   */
  public TScanRangeSpec getScanRangeSpecs() {
    Preconditions.checkNotNull(scanRangeSpecs_, "Need to call init() first.");
    return scanRangeSpecs_;
  }

  @Override
  protected String debugString() {
    return Objects.toStringHelper(this)
        .add("tid", desc_.getId().asInt())
        .add("tblName", desc_.getTable().getFullName())
        .add("keyRanges", "")
        .addValue(super.debugString())
        .toString();
  }

  /**
   * Returns the explain string for table stats to be included into this ScanNode's
   * explain string. The prefix is prepended to each returned line for proper formatting
   * when the string returned by this method is embedded in a query's explain plan.
   */
  protected String getTableStatsExplainString(String prefix) {
    StringBuilder output = new StringBuilder();
    TTableStats tblStats = desc_.getTable().getTTableStats();
    String numRows = String.valueOf(tblStats.num_rows);
    if (tblStats.num_rows == -1) numRows = "unavailable";
    output.append(prefix + "table: rows=" + numRows);
    return output.toString();
  }

  /**
   * Returns the explain string for column stats to be included into this ScanNode's
   * explain string. The prefix is prepended to each returned line for proper formatting
   * when the string returned by this method is embedded in a query's explain plan.
   */
  protected String getColumnStatsExplainString(String prefix) {
    StringBuilder output = new StringBuilder();
    List<String> columnsMissingStats = Lists.newArrayList();
    for (SlotDescriptor slot: desc_.getSlots()) {
      if (!slot.getStats().hasStats() && slot.getColumn() != null) {
        columnsMissingStats.add(slot.getColumn().getName());
      }
    }
    if (columnsMissingStats.isEmpty()) {
      output.append(prefix + "columns: all");
    } else if (columnsMissingStats.size() == desc_.getSlots().size()) {
      output.append(prefix + "columns: unavailable");
    } else {
      output.append(String.format("%scolumns missing stats: %s", prefix,
          Joiner.on(", ").join(columnsMissingStats)));
    }
    return output.toString();
  }

  /**
   * Combines the explain string for table and column stats.
   */
  protected String getStatsExplainString(String prefix) {
    StringBuilder output = new StringBuilder(prefix);
    output.append("stored statistics:\n");
    prefix = prefix + "  ";
    output.append(getTableStatsExplainString(prefix));
    output.append("\n");
    output.append(getColumnStatsExplainString(prefix));
    return output.toString();
  }

  /**
   * Returns true if the table underlying this scan is missing table stats
   * or column stats relevant to this scan node.
   */
  public boolean isTableMissingStats() {
    return isTableMissingColumnStats() || isTableMissingTableStats();
  }

  public boolean isTableMissingTableStats() {
    return desc_.getTable().getNumRows() == -1;
  }

  /**
   * Returns true if the tuple descriptor references a path with a collection type.
   */
  public boolean isAccessingCollectionType() {
    for (Type t: desc_.getPath().getMatchedTypes()) {
      if (t.isCollectionType()) return true;
    }
    return false;
  }

  public boolean isTableMissingColumnStats() {
    for (SlotDescriptor slot: desc_.getSlots()) {
      if (slot.getColumn() != null && !slot.getStats().hasStats()) return true;
    }
    return false;
  }

  /**
   * Returns true, if the scanned table is suspected to have corrupt table stats,
   * in particular, if the scan is non-empty and 'numRows' is 0 or negative (but not -1).
   */
  public boolean hasCorruptTableStats() { return false; }

  /**
   * Helper function to parse a "host:port" address string into TNetworkAddress
   * This is called with ipaddress:port when doing scan range assignment.
   */
  protected static TNetworkAddress addressToTNetworkAddress(String address) {
    TNetworkAddress result = new TNetworkAddress();
    String[] hostPort = address.split(":");
    result.hostname = hostPort[0];
    result.port = Integer.parseInt(hostPort[1]);
    return result;
  }

  @Override
  public long getInputCardinality() {
    if (!hasScanConjuncts() && !hasStorageLayerConjuncts() && hasLimit()) {
      return getLimit();
    }
    return inputCardinality_;
  }

  @Override
  protected String getDisplayLabelDetail() {
    Table table = desc_.getTable();
    List<String> path = Lists.newArrayList();
    path.add(table.getDb().getName());
    path.add(table.getName());
    Preconditions.checkNotNull(desc_.getPath());
    if (desc_.hasExplicitAlias()) {
      return desc_.getPath().toString() + " " + desc_.getAlias();
    } else {
      return desc_.getPath().toString();
    }
  }

  /**
   * Returns true if this node has conjuncts to be evaluated by Impala against the scan
   * tuple.
   */
  public boolean hasScanConjuncts() { return !getConjuncts().isEmpty(); }

  /**
   * Returns true if this node has conjuncts to be evaluated by the underlying storage
   * engine.
   */
  public boolean hasStorageLayerConjuncts() { return false; }
}
