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

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.bson.BsonDateTime;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static org.restheart.graphql.scalars.bsonCoercing.CoercingUtils.typeName;

public class GraphQLBsonDateCoercing implements Coercing<BsonDateTime, BsonDateTime>  {
    @Override
    public BsonDateTime serialize(Object dataFetcherResult) throws CoercingSerializeException {
        if (dataFetcherResult instanceof BsonDateTime bsonDateTime){
            return bsonDateTime;
        } else {
            throw new CoercingSerializeException("Expected type 'BsonDateTime' but was '" + typeName(dataFetcherResult) +"'.");
        }
    }

    @Override
    public BsonDateTime parseValue(Object input) throws CoercingParseValueException {
        var possibleDate = convertImpl(input);
        if (possibleDate == null){
            throw new CoercingParseValueException("Expected type 'Long' or 'String' (with a valid OffsetDateTime) but was '" + typeName(input) +"'.");
        } else {
            return new BsonDateTime(possibleDate);
        }
    }

    @Override
    public BsonDateTime parseLiteral(Object AST) throws CoercingParseLiteralException {
        if (AST instanceof StringValue stringValue) {
            var possibleDate = stringValue.getValue();
            try {
                var ofsDate = OffsetDateTime.parse(possibleDate, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return new BsonDateTime(ofsDate.toInstant().toEpochMilli());
            } catch (DateTimeParseException dtpe) {
                try {
                    return new BsonDateTime(Long.parseLong(possibleDate));
                } catch (NumberFormatException e2){
                    throw new CoercingParseLiteralException("Input string is not a valid date.");
                }
            }
        } else {
            throw new CoercingParseLiteralException("Expected AST type 'StringValue' but was '" + typeName(AST) + "'.");
        }
    }

    private Long convertImpl(Object input){
        if (input instanceof Long _long){
            return _long;
        } else if (input instanceof String string){
            try {
                var ofsDate = OffsetDateTime.parse(string, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return ofsDate.toInstant().toEpochMilli();
            } catch (DateTimeParseException dtpe){
                try{
                    return Long.parseLong(string);
                } catch (NumberFormatException nfe) {
                    return null;
                }
            }
        } else if (input instanceof OffsetDateTime offsetDateTime){
            return offsetDateTime.toInstant().toEpochMilli();
        }

        return null;
    }
}
