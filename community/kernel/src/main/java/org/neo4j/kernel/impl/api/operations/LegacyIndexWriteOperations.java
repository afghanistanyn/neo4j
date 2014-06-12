/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.operations;

import java.util.Map;

import org.neo4j.kernel.impl.api.KernelStatement;

public interface LegacyIndexWriteOperations
{
    void nodeLegacyIndexCreateLazily( KernelStatement statement, String indexName, Map<String, String> customConfig );

    void relationshipLegacyIndexCreateLazily( KernelStatement statement, String indexName,
            Map<String, String> customConfig );

    void nodeAddToLegacyIndex( KernelStatement statement, String indexName, long node, String key, Object value );

    void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node, String key, Object value );

    void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node, String key );

    void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node );

    void relationshipAddToLegacyIndex( KernelStatement statement, String indexName, long relationship, String key,
            Object value, long startNode, long endNode );

    void relationshipRemoveFromLegacyIndex( KernelStatement statement, String indexName, long relationship, String key,
            Object value );

    void relationshipRemoveFromLegacyIndex( KernelStatement statement, String indexName, long relationship, String key );

    void relationshipRemoveFromLegacyIndex( KernelStatement statement, String indexName, long relationship );

    void nodeLegacyIndexDrop( KernelStatement statement, String indexName );

    void relationshipLegacyIndexDrop( KernelStatement statement, String indexName );

    long nodeLegacyIndexPutIfAbsent( KernelStatement statement, long node, String key, Object value );

    long relationshipLegacyIndexPutIfAbsent( KernelStatement statement, long relationship, String key, Object value );
}
