/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.config.*;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.db.composites.CellNames;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.transport.messages.ResultMessage;

public abstract class AlterTypeStatement extends SchemaAlteringStatement
{
    protected final UTName name;

    protected AlterTypeStatement(UTName name)
    {
        super();
        this.name = name;
    }

    @Override
    public void prepareKeyspace(ClientState state) throws InvalidRequestException
    {
        if (!name.hasKeyspace())
            name.setKeyspace(state.getKeyspace());

        if (name.getKeyspace() == null)
            throw new InvalidRequestException("You need to be logged in a keyspace or use a fully qualified user type name");
    }

    protected abstract UserType makeUpdatedType(UserType toUpdate) throws InvalidRequestException;

    public static AlterTypeStatement addition(UTName name, ColumnIdentifier fieldName, CQL3Type.Raw type)
    {
        return new AddOrAlter(name, true, fieldName, type);
    }

    public static AlterTypeStatement alter(UTName name, ColumnIdentifier fieldName, CQL3Type.Raw type)
    {
        return new AddOrAlter(name, false, fieldName, type);
    }

    //重合名类型中的一个或多个字段，如:
    //1个字段: ALTER TYPE type1 RENAME f1 TO f11
    //多个字段:  ALTER TYPE type1 RENAME f1 TO f11 AND f2 TO f22 
    public static AlterTypeStatement renames(UTName name, Map<ColumnIdentifier, ColumnIdentifier> renames)
    {
        return new Renames(name, renames);
    }

    //重合名类型自身，如: ALTER TYPE type1 RENAME TO type2
    public static AlterTypeStatement typeRename(UTName name, UTName newName)
    {
        return new TypeRename(name, newName);
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        state.hasKeyspaceAccess(keyspace(), Permission.ALTER);
    }

    public void validate(ClientState state) throws RequestValidationException
    {
        // Validation is left to announceMigration as it's easier to do it while constructing the updated type.
        // It doesn't really change anything anyway.
    }

    public ResultMessage.SchemaChange.Change changeType()
    {
        return ResultMessage.SchemaChange.Change.UPDATED;
    }

    @Override
    public String keyspace()
    {
        return name.getKeyspace();
    }

    public void announceMigration() throws InvalidRequestException, ConfigurationException
    {
        KSMetaData ksm = Schema.instance.getKSMetaData(name.getKeyspace());
        if (ksm == null)
            throw new InvalidRequestException(String.format("Cannot alter type in unknown keyspace %s", name.getKeyspace()));

        UserType toUpdate = ksm.userTypes.getType(name.getUserTypeName());
        // Shouldn't happen, unless we race with a drop
        if (toUpdate == null)
            throw new InvalidRequestException(String.format("No user type named %s exists.", name));

        UserType updated = makeUpdatedType(toUpdate);

        // Now, we need to announce the type update to basically change it for new tables using this type,
        // but we also need to find all existing user types and CF using it and change them.
        MigrationManager.announceTypeUpdate(updated);

        for (KSMetaData ksm2 : Schema.instance.getKeyspaceDefinitions())
        {
            for (CFMetaData cfm : ksm2.cfMetaData().values())
            {
                CFMetaData copy = cfm.clone();
                boolean modified = false;
                for (ColumnDefinition def : copy.allColumns())
                    modified |= updateDefinition(copy, def, toUpdate.keyspace, toUpdate.name, updated);
                if (modified)
                    MigrationManager.announceColumnFamilyUpdate(copy, false);
            }

            // Other user types potentially using the updated type
            for (UserType ut : ksm2.userTypes.getAllTypes().values())
            {
                // Re-updating the type we've just updated would be harmless but useless so we avoid it.
                // Besides, we use the occasion to drop the old version of the type if it's a type rename
                if (ut.keyspace.equals(toUpdate.keyspace) && ut.name.equals(toUpdate.name))
                {
                    if (!ut.keyspace.equals(updated.keyspace) || !ut.name.equals(updated.name))
                        MigrationManager.announceTypeDrop(ut);
                    continue;
                }
                AbstractType<?> upd = updateWith(ut, toUpdate.keyspace, toUpdate.name, updated);
                if (upd != null)
                    MigrationManager.announceTypeUpdate((UserType)upd);
            }
        }
    }

    private static int getIdxOfField(UserType type, ColumnIdentifier field)
    {
        for (int i = 0; i < type.types.size(); i++)
            if (field.bytes.equals(type.columnNames.get(i)))
                return i;
        return -1;
    }

    private boolean updateDefinition(CFMetaData cfm, ColumnDefinition def, String keyspace, ByteBuffer toReplace, UserType updated)
    {
        AbstractType<?> t = updateWith(def.type, keyspace, toReplace, updated);
        if (t == null)
            return false;

        // We need to update this validator ...
        cfm.addOrReplaceColumnDefinition(def.withNewType(t));

        // ... but if it's part of the comparator or key validator, we need to go update those too.
        switch (def.kind)
        {
            case PARTITION_KEY:
                cfm.keyValidator(updateWith(cfm.getKeyValidator(), keyspace, toReplace, updated));
                break;
            case CLUSTERING_COLUMN:
                cfm.comparator = CellNames.fromAbstractType(updateWith(cfm.comparator.asAbstractType(), keyspace, toReplace, updated), cfm.comparator.isDense());
                break;
            default:
                // If it's a collection, we still want to modify the comparator because the collection is aliased in it
                if (def.type instanceof CollectionType)
                    cfm.comparator = CellNames.fromAbstractType(updateWith(cfm.comparator.asAbstractType(), keyspace, toReplace, updated), cfm.comparator.isDense());
        }
        return true;
    }

    // Update the provided type were all instance of a given userType is replaced by a new version
    // Note that this methods reaches inside other UserType, CompositeType and CollectionType.
    private static AbstractType<?> updateWith(AbstractType<?> type, String keyspace, ByteBuffer toReplace, UserType updated)
    {
        if (type instanceof UserType)
        {
            UserType ut = (UserType)type;

            // If it's directly the type we've updated, then just use the new one.
            if (keyspace.equals(ut.keyspace) && toReplace.equals(ut.name))
                return updated;

            // Otherwise, check for nesting
            List<AbstractType<?>> updatedTypes = updateTypes(ut.types, keyspace, toReplace, updated);
            return updatedTypes == null ? null : new UserType(ut.keyspace, ut.name, new ArrayList<>(ut.columnNames), updatedTypes);
        }
        else if (type instanceof CompositeType)
        {
            CompositeType ct = (CompositeType)type;
            List<AbstractType<?>> updatedTypes = updateTypes(ct.types, keyspace, toReplace, updated);
            return updatedTypes == null ? null : CompositeType.getInstance(updatedTypes);
        }
        else if (type instanceof ColumnToCollectionType)
        {
            ColumnToCollectionType ctct = (ColumnToCollectionType)type;
            Map<ByteBuffer, CollectionType> updatedTypes = null;
            for (Map.Entry<ByteBuffer, CollectionType> entry : ctct.defined.entrySet())
            {
                AbstractType<?> t = updateWith(entry.getValue(), keyspace, toReplace, updated);
                if (t == null)
                    continue;

                if (updatedTypes == null)
                    updatedTypes = new HashMap<>(ctct.defined);

                updatedTypes.put(entry.getKey(), (CollectionType)t);
            }
            return updatedTypes == null ? null : ColumnToCollectionType.getInstance(updatedTypes);
        }
        else if (type instanceof CollectionType)
        {
            if (type instanceof ListType)
            {
                AbstractType<?> t = updateWith(((ListType)type).elements, keyspace, toReplace, updated);
                return t == null ? null : ListType.getInstance(t);
            }
            else if (type instanceof SetType)
            {
                AbstractType<?> t = updateWith(((SetType)type).elements, keyspace, toReplace, updated);
                return t == null ? null : SetType.getInstance(t);
            }
            else
            {
                assert type instanceof MapType;
                MapType mt = (MapType)type;
                AbstractType<?> k = updateWith(mt.keys, keyspace, toReplace, updated);
                AbstractType<?> v = updateWith(mt.values, keyspace, toReplace, updated);
                if (k == null && v == null)
                    return null;
                return MapType.getInstance(k == null ? mt.keys : k, v == null ? mt.values : v);
            }
        }
        else
        {
            return null;
        }
    }

    private static List<AbstractType<?>> updateTypes(List<AbstractType<?>> toUpdate, String keyspace, ByteBuffer toReplace, UserType updated)
    {
        // But this can also be nested.
        List<AbstractType<?>> updatedTypes = null;
        for (int i = 0; i < toUpdate.size(); i++)
        {
            AbstractType<?> t = updateWith(toUpdate.get(i), keyspace, toReplace, updated);
            if (t == null)
                continue;

            if (updatedTypes == null)
                updatedTypes = new ArrayList<>(toUpdate);

            updatedTypes.set(i, t);
        }
        return updatedTypes;
    }

    private static class AddOrAlter extends AlterTypeStatement
    {
        private final boolean isAdd;
        private final ColumnIdentifier fieldName;
        private final CQL3Type.Raw type;

        public AddOrAlter(UTName name, boolean isAdd, ColumnIdentifier fieldName, CQL3Type.Raw type)
        {
            super(name);
            this.isAdd = isAdd;
            this.fieldName = fieldName;
            this.type = type;
        }

        private UserType doAdd(UserType toUpdate) throws InvalidRequestException
        {
            if (getIdxOfField(toUpdate, fieldName) >= 0)
                throw new InvalidRequestException(String.format("Cannot add new field %s to type %s: a field of the same name already exists", fieldName, name));

            List<ByteBuffer> newNames = new ArrayList<>(toUpdate.columnNames.size() + 1);
            newNames.addAll(toUpdate.columnNames);
            newNames.add(fieldName.bytes);

            List<AbstractType<?>> newTypes = new ArrayList<>(toUpdate.types.size() + 1);
            newTypes.addAll(toUpdate.types);
            newTypes.add(type.prepare(keyspace()).getType());

            return new UserType(toUpdate.keyspace, toUpdate.name, newNames, newTypes);
        }

        private UserType doAlter(UserType toUpdate) throws InvalidRequestException
        {
            int idx = getIdxOfField(toUpdate, fieldName);
            if (idx < 0)
                throw new InvalidRequestException(String.format("Unknown field %s in type %s", fieldName, name));

            AbstractType<?> previous = toUpdate.types.get(idx);
            if (!type.prepare(keyspace()).getType().isCompatibleWith(previous))
                throw new InvalidRequestException(String.format("Type %s is incompatible with previous type %s of field %s in user type %s", type, previous.asCQL3Type(), fieldName, name));

            List<ByteBuffer> newNames = new ArrayList<>(toUpdate.columnNames);
            List<AbstractType<?>> newTypes = new ArrayList<>(toUpdate.types);
            newTypes.set(idx, type.prepare(keyspace()).getType());

            return new UserType(toUpdate.keyspace, toUpdate.name, newNames, newTypes);
        }

        protected UserType makeUpdatedType(UserType toUpdate) throws InvalidRequestException
        {
            return isAdd ? doAdd(toUpdate) : doAlter(toUpdate);
        }
    }

    private static class Renames extends AlterTypeStatement
    {
        private final Map<ColumnIdentifier, ColumnIdentifier> renames;

        public Renames(UTName name, Map<ColumnIdentifier, ColumnIdentifier> renames)
        {
            super(name);
            this.renames = renames;
        }

        protected UserType makeUpdatedType(UserType toUpdate) throws InvalidRequestException
        {
            List<ByteBuffer> newNames = new ArrayList<>(toUpdate.columnNames);
            List<AbstractType<?>> newTypes = new ArrayList<>(toUpdate.types);

            for (Map.Entry<ColumnIdentifier, ColumnIdentifier> entry : renames.entrySet())
            {
                ColumnIdentifier from = entry.getKey();
                ColumnIdentifier to = entry.getValue();
                int idx = getIdxOfField(toUpdate, from);
                if (idx < 0)
                    throw new InvalidRequestException(String.format("Unknown field %s in type %s", from, name));
                newNames.set(idx, to.bytes);
            }

            UserType updated = new UserType(toUpdate.keyspace, toUpdate.name, newNames, newTypes);
            CreateTypeStatement.checkForDuplicateNames(updated);
            return updated;
        }

    }

    private static class TypeRename extends AlterTypeStatement
    {
        private final UTName newName;

        public TypeRename(UTName name, UTName newName)
        {
            super(name);
            this.newName = newName;
        }

        @Override
        public void prepareKeyspace(ClientState state) throws InvalidRequestException
        {
            super.prepareKeyspace(state);

            if (!newName.hasKeyspace())
                newName.setKeyspace(state.getKeyspace());

            if (newName.getKeyspace() == null)
                throw new InvalidRequestException("You need to be logged in a keyspace or use a fully qualified user type name");
        }

        protected UserType makeUpdatedType(UserType toUpdate) throws InvalidRequestException
        {
            KSMetaData ksm = Schema.instance.getKSMetaData(newName.getKeyspace());
            if (ksm == null)
                throw new InvalidRequestException("Unknown keyspace " + newName.getKeyspace());

            UserType previous = ksm.userTypes.getType(newName.getUserTypeName());
            if (previous != null)
                throw new InvalidRequestException(String.format("Cannot rename user type %s to %s as another type of that name exists", name, newName));

            return new UserType(newName.getKeyspace(), newName.getUserTypeName(), new ArrayList<>(toUpdate.columnNames), new ArrayList<>(toUpdate.types));
        }
    }
}
