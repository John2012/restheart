/*-
 * ========================LICENSE_START=================================
 * restheart-graphql
 * %%
 * Copyright (C) 2020 - 2021 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart.graphql.scalars.bsonCoercing;

import graphql.schema.*;
import org.bson.BsonInt32;

import static org.restheart.graphql.scalars.bsonCoercing.CoercingUtils.typeName;


public class GraphQLBsonInt32Coercing implements Coercing<BsonInt32, BsonInt32> {

    @Override
    public BsonInt32 serialize(Object dataFetcherResult) throws CoercingSerializeException {
        if(dataFetcherResult instanceof BsonInt32 bsonInt32) {
            return bsonInt32;
        } else {
            throw new CoercingSerializeException("Expected type 'BsonInt32' but was '" + typeName(dataFetcherResult) +"'.");
        }
    }

    @Override
    public BsonInt32 parseValue(Object input) {
        return new BsonInt32((Integer) CoercingUtils.builtInCoercing.get("Int").parseValue(input));
    }

    @Override
    public BsonInt32 parseLiteral(Object AST) {
        return new BsonInt32((Integer) CoercingUtils.builtInCoercing.get("Int").parseLiteral(AST));
    }
}
