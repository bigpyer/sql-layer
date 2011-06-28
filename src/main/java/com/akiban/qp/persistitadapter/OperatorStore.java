/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.GroupIndex;
import com.akiban.ais.model.GroupTable;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.TableIndex;
import com.akiban.ais.model.UserTable;
import com.akiban.message.ErrorCode;
import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.exec.UpdateResult;
import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.physicaloperator.API;
import com.akiban.qp.physicaloperator.ArrayBindings;
import com.akiban.qp.physicaloperator.Bindings;
import com.akiban.qp.physicaloperator.Cursor;
import com.akiban.qp.physicaloperator.CursorUpdateException;
import com.akiban.qp.physicaloperator.PhysicalOperator;
import com.akiban.qp.physicaloperator.UndefBindings;
import com.akiban.qp.physicaloperator.UpdateFunction;
import com.akiban.qp.physicaloperator.Update_Default;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.qp.util.SchemaCache;
import com.akiban.server.InvalidOperationException;
import com.akiban.server.RowData;
import com.akiban.server.RowDef;
import com.akiban.server.api.dml.ColumnSelector;
import com.akiban.server.api.dml.ConstantColumnSelector;
import com.akiban.server.api.dml.DuplicateKeyException;
import com.akiban.server.api.dml.NoSuchRowException;
import com.akiban.server.api.dml.scan.LegacyRowWrapper;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.api.dml.scan.NiceRow;
import com.akiban.server.service.ServiceManagerImpl;
import com.akiban.server.service.session.Session;
import com.akiban.server.store.DelegatingStore;
import com.akiban.server.store.PersistitStore;
import com.akiban.util.CachePair;
import com.persistit.Exchange;
import com.persistit.Transaction;
import com.persistit.exception.PersistitException;
import com.persistit.exception.RollbackException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.akiban.qp.physicaloperator.API.ancestorLookup_Default;
import static com.akiban.qp.physicaloperator.API.indexScan_Default;

public class OperatorStore extends DelegatingStore<PersistitStore> {

    // Store interface

    @Override
    public void updateRow(Session session, RowData oldRowData, RowData newRowData, ColumnSelector columnSelector)
            throws Exception
    {
        PersistitStore persistitStore = getPersistitStore();
        AkibanInformationSchema ais = persistitStore.getRowDefCache().ais();

        RowDef rowDef = persistitStore.getRowDefCache().rowDef(oldRowData.getRowDefId());
        if ((columnSelector != null) && !rowDef.table().getGroupIndexes().isEmpty()) {
            throw new RuntimeException("group index maintence won't work with partial rows");
        }

        PersistitAdapter adapter = new PersistitAdapter(SchemaCache.globalSchema(ais), persistitStore, session);
        Schema schema = adapter.schema();

        UpdateFunction updateFunction = new InternalUpdateFunction(adapter, rowDef, newRowData, columnSelector);

        UserTable userTable = ais.getUserTable(oldRowData.getRowDefId());
        GroupTable groupTable = userTable.getGroup().getGroupTable();

        TableIndex index = userTable.getPrimaryKeyIncludingInternal().getIndex();
        assert index != null : userTable;
        UserTableRowType tableType = schema.userTableRowType(userTable);
        IndexRowType indexType = tableType.indexRowType(index);
        IndexBound bound = new IndexBound(new NewRowBackedIndexRow(tableType, new LegacyRowWrapper(oldRowData), index),
                                          ConstantColumnSelector.ALL_ON);
        IndexKeyRange range = new IndexKeyRange(bound, true, bound, true);

        PhysicalOperator indexScan = indexScan_Default(indexType, false, range);
        PhysicalOperator scanOp;
        scanOp = ancestorLookup_Default(indexScan, groupTable, indexType, Collections.singletonList(tableType), false);

        // MVCC will render this useless, but for now, a limit of 1 ensures we won't see the row we just updated,
        // and therefore scan through two rows -- once to update old -> new, then to update new -> copy of new
        scanOp = com.akiban.qp.physicaloperator.API.limit_Default(scanOp, 1);

        Update_Default updateOp = new Update_Default(scanOp, updateFunction);

        Transaction transaction = ServiceManagerImpl.get().getTreeService().getTransaction(session);
        for(int retryCount=0; ; ++retryCount) {
            try {
                transaction.begin();

                maintainGroupIndexes(
                        session, ais, adapter,
                        oldRowData, new OperatorStoreGIHandler(adapter, new OperatorStoreGIHandler.RowAction(userTable, OperatorStoreGIHandler.Action.DELETE))
                );

                runCursor(oldRowData, rowDef, updateOp, adapter);

                maintainGroupIndexes(
                        session, ais, adapter,
                        newRowData, new OperatorStoreGIHandler(adapter, new OperatorStoreGIHandler.RowAction(userTable, OperatorStoreGIHandler.Action.STORE))
                );

                transaction.commit();
                break;
            } catch (RollbackException e) {
                if (retryCount >= MAX_RETRIES) {
                    throw e;
                }
            } finally {
                transaction.end();
            }
        }
    }

    @Override
    public void writeRow(Session session, RowData rowData) throws Exception {
        Transaction transaction = ServiceManagerImpl.get().getTreeService().getTransaction(session);
        for(int retryCount=0; ; ++retryCount) {
            try {
                transaction.begin();
                super.writeRow(session, rowData);

                AkibanInformationSchema ais = ServiceManagerImpl.get().getDXL().ddlFunctions().getAIS(session);
                PersistitAdapter adapter = new PersistitAdapter(SchemaCache.globalSchema(ais), getPersistitStore(), session);
                UserTable uTable = ais.getUserTable(rowData.getRowDefId());
                maintainGroupIndexes(
                        session, ais, adapter,
                        rowData, new OperatorStoreGIHandler(adapter, new OperatorStoreGIHandler.RowAction(uTable, OperatorStoreGIHandler.Action.STORE))
                );

                transaction.commit();
                break;
            } catch (RollbackException e) {
                if (retryCount >= MAX_RETRIES) {
                    throw e;
                }
            } finally {
                transaction.end();
            }
        }
    }

    @Override
    public void deleteRow(Session session, RowData rowData) throws Exception {
        Transaction transaction = ServiceManagerImpl.get().getTreeService().getTransaction(session);
        for(int retryCount=0; ; ++retryCount) {
            try {
                transaction.begin();
                AkibanInformationSchema ais = ServiceManagerImpl.get().getDXL().ddlFunctions().getAIS(session);
                PersistitAdapter adapter = new PersistitAdapter(SchemaCache.globalSchema(ais), getPersistitStore(), session);
                UserTable uTable = ais.getUserTable(rowData.getRowDefId());
                maintainGroupIndexes(
                        session, ais, adapter,
                        rowData, new OperatorStoreGIHandler(adapter, new OperatorStoreGIHandler.RowAction(uTable, OperatorStoreGIHandler.Action.DELETE))
                );
                super.deleteRow(session, rowData);
                transaction.commit();
                break;
            } catch (RollbackException e) {
                if (retryCount >= MAX_RETRIES) {
                    throw e;
                }
            } finally {
                transaction.end();
            }
        }
    }

    @Override
    public void buildIndexes(Session session, Collection<? extends Index> indexes, boolean defer) throws Exception {
        List<TableIndex> tableIndexes = new ArrayList<TableIndex>();
        List<GroupIndex> groupIndexes = new ArrayList<GroupIndex>();
        for(Index index : indexes) {
            if(index.isTableIndex()) {
                tableIndexes.add((TableIndex)index);
            }
            else if(index.isGroupIndex()) {
                groupIndexes.add((GroupIndex)index);
            }
            else {
                throw new IllegalArgumentException("Unknown index type: " + index);
            }
        }

        if(!tableIndexes.isEmpty()) {
            super.buildIndexes(session, tableIndexes, defer);
        }

        AkibanInformationSchema ais = ServiceManagerImpl.get().getDXL().ddlFunctions().getAIS(session);
        PersistitAdapter adapter = new PersistitAdapter(SchemaCache.globalSchema(ais), getPersistitStore(), session);
        for(GroupIndex groupIndex : groupIndexes) {
            PhysicalOperator plan = MaintenancePlanCreator.groupIndexCreationPlan(adapter.schema(), groupIndex);
            runMaintenancePlan(adapter, groupIndex, plan, UndefBindings.only(),
                    new OperatorStoreGIHandler(adapter, OperatorStoreGIHandler.RowAction.FOR_BULK));
        }
    }

    // OperatorStore interface

    public OperatorStore() {
        super(new PersistitStore(false));
    }

    public PersistitStore getPersistitStore() {
        return super.getDelegate();
    }

    // for use by subclasses

    protected final <T extends Throwable> void maintainGroupIndexes(
            Session session,
            RowData rowData,
            GroupIndexHandler<T> handler
    )
    throws PersistitException, T
    {
        AkibanInformationSchema ais = ServiceManagerImpl.get().getDXL().ddlFunctions().getAIS(session);
        PersistitAdapter adapter = new PersistitAdapter(SchemaCache.globalSchema(ais), getPersistitStore(), session);
        maintainGroupIndexes(session, ais, adapter, rowData, handler);
    }

    protected Collection<GroupIndex> optionallyOrderGroupIndexes(Collection<GroupIndex> groupIndexes) {
        return groupIndexes;
    }

    // private methods

    private <T extends Throwable> void maintainGroupIndexes(
            Session session,
            AkibanInformationSchema ais, PersistitAdapter adapter,
            RowData rowData,
            GroupIndexHandler<T> handler
    )
    throws PersistitException, T
    {
        UserTable userTable = ais.getUserTable(rowData.getRowDefId());

        Exchange hEx = adapter.takeExchange(userTable.getGroup().getGroupTable());
        try {
            // the "false" at the end of constructHKey toggles whether the RowData should be modified to increment
            // the hidden PK field, if there is one. For PK-less rows, this field have already been incremented by now,
            // so we don't want to increment it again
            getPersistitStore().constructHKey(session, hEx, (RowDef) userTable.rowDef(), rowData, false);
            PersistitHKey persistitHKey = new PersistitHKey(adapter, userTable.hKey());
            persistitHKey.copyFrom(hEx.getKey());

            ArrayBindings bindings = new ArrayBindings(1);
            bindings.set(MaintenancePlanCreator.HKEY_BINDING_POSITION, persistitHKey);

            Collection<GroupIndex> branchIndexes = new ArrayList<GroupIndex>();
            for (GroupIndex groupIndex : userTable.getGroup().getIndexes()) {
                if (groupIndex.leafMostTable().isDescendantOf(userTable)) {
                    branchIndexes.add(groupIndex);
                }
            }

            for (GroupIndex groupIndex : optionallyOrderGroupIndexes(branchIndexes)) {
                if (groupIndex.isUnique()) {
                    throw new UniqueIndexUnsupportedException();
                }
                PhysicalOperator plan = groupIndexCreationPlan(
                        ais,
                        groupIndex,
                        adapter.schema().userTableRowType(userTable)
                );
                runMaintenancePlan(adapter, groupIndex, plan, bindings, handler);
            }
        } finally {
            adapter.returnExchange(hEx);
        }
    }

    private <T extends Throwable> void runMaintenancePlan(
            PersistitAdapter adapter,
            GroupIndex groupIndex,
            PhysicalOperator rootOperator,
            Bindings bindings,
            GroupIndexHandler<T> handler
    )
    throws T
    {
        Cursor cursor = API.cursor(rootOperator, adapter);
        cursor.open(bindings);
        try {
            Row row;
            while ((row = cursor.next()) != null) {
                if (row.rowType().equals(rootOperator.rowType())) {
                    handler.handleRow(groupIndex, row);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private PhysicalOperator groupIndexCreationPlan(
            AkibanInformationSchema ais, GroupIndex groupIndex, UserTableRowType rowType
    ) {
        Map<GroupIndex, Map<UserTableRowType,PhysicalOperator>> gisToPlansMapMap = maintenancePlans.get(ais);
        Map<UserTableRowType,PhysicalOperator> plansMap = gisToPlansMapMap.get(groupIndex);
        if (plansMap == null) {
            throw new RuntimeException("no plan found for group index " + groupIndex);
        }
        PhysicalOperator plan = plansMap.get(rowType);
        if (plan == null) {
            throw new RuntimeException("no plan for row type " + rowType + " in group index " + groupIndex);
        }
        return plan;
    }

    // private static methods

    private static void runCursor(RowData oldRowData, RowDef rowDef, UpdatePlannable plannable, PersistitAdapter adapter)
            throws DuplicateKeyException, NoSuchRowException
    {
        final UpdateResult result;
        try {
            result = plannable.run(UndefBindings.only(), adapter);
        } catch (CursorUpdateException e) {
            Throwable cause = e.getCause();
            if ( (cause instanceof InvalidOperationException)
                    && ErrorCode.DUPLICATE_KEY.equals(((InvalidOperationException) cause).getCode()))
            {
                throw new DuplicateKeyException((InvalidOperationException)cause);
            }
            throw e;
        }

        if (result.rowsModified() == 0 || result.rowsTouched() == 0) {
            throw new NoSuchRowException(describeRow(oldRowData, rowDef));
        }
        else if(result.rowsModified() != 1 || result.rowsTouched() != 1) {
            throw new RuntimeException(String.format(
                    "%s: %d touched, %d modified",
                    describeRow(oldRowData, rowDef),
                    result.rowsTouched(),
                    result.rowsModified()
            ));
        }
    }

    private static String describeRow(RowData oldRowData, RowDef rowDef) {
        String rowDescription;
        try {
            rowDescription = oldRowData.toString(rowDef);
        } catch (Exception e) {
            rowDescription = "error in generating RowData.toString";
        }
        return rowDescription;
    }

    // object state

    private final CachePair<AkibanInformationSchema, Map<GroupIndex, Map<UserTableRowType,PhysicalOperator>>> maintenancePlans
            = CachePair.using(new MaintenancePlanCreator());

    // consts
    private static final int MAX_RETRIES = 10;

    // nested classes

    private static class InternalUpdateFunction implements UpdateFunction {
        private final PersistitAdapter adapter;
        private final RowData newRowData;
        private final ColumnSelector columnSelector;
        private final RowDef rowDef;

        private InternalUpdateFunction(PersistitAdapter adapter, RowDef rowDef, RowData newRowData, ColumnSelector columnSelector) {
            this.newRowData = newRowData;
            this.columnSelector = columnSelector;
            this.rowDef = rowDef;
            this.adapter = adapter;
        }

        @Override
        public boolean rowIsSelected(Row row) {
            return row.rowType().typeId() == rowDef.getRowDefId();
        }

        @Override
        public Row evaluate(Row original, Bindings bindings) {
            // TODO
            // ideally we'd like to use an OverlayingRow, but ModifiablePersistitGroupCursor requires
            // a PersistitGroupRow if an hkey changes
//            OverlayingRow overlay = new OverlayingRow(original);
//            for (int i=0; i < rowDef.getFieldCount(); ++i) {
//                if (columnSelector == null || columnSelector.includesColumn(i)) {
//                    overlay.overlay(i, newRowData.toObject(rowDef, i));
//                }
//            }
//            return overlay;
            // null selector means all cols, so we can skip the merging and just return the new row data
            if (columnSelector == null) {
                return PersistitGroupRow.newPersistitGroupRow(adapter, newRowData);
            }
            // Note: some encodings are untested except as necessary for mtr
            NewRow newRow = new NiceRow(rowDef.getRowDefId());
            for (int i=0; i < original.rowType().nFields(); ++i) {
                if (columnSelector.includesColumn(i)) {
                    newRow.put(i, newRowData.toObject(rowDef, i));
                }
                else {
                    newRow.put(i, original.field(i, bindings));
                }
            }
            return PersistitGroupRow.newPersistitGroupRow(adapter, newRow.toRowData());
        }
    }
    
    protected interface GroupIndexHandler<T extends Throwable> {
        void handleRow(GroupIndex groupIndex, Row row) throws T;
    }

    public class UniqueIndexUnsupportedException extends UnsupportedOperationException {
        public UniqueIndexUnsupportedException() {
            super("unique indexes not supported");
        }
    }
}
