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
package org.restheart.graphql.cache;

import com.mongodb.MongoClient;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.restheart.graphql.GraphQLAppDeserializer;
import org.restheart.graphql.GraphQLIllegalAppDefinitionException;
import org.restheart.graphql.models.*;
import static org.restheart.utils.BsonUtils.arrayBuilder;;


public class AppDefinitionLoader {

    private static final String APP_URI_FIELD = "descriptor.uri";
    private static final String APP_NAME_FIELD = "descriptor.name";
    private static final String APP_ENABLED_FIELD = "descriptor.enabled";

    private static MongoClient mongoClient;
    private static String appDB;
    private static String appCollection;

    public static void setup(String _db, String _collection, MongoClient mclient){
        appDB = _db;
        appCollection = _collection;
        mongoClient = mclient;
    }

    public static GraphQLApp loadAppDefinition(String appURI) throws GraphQLIllegalAppDefinitionException {
        var uriOrNameCond = arrayBuilder()
            .add(new BsonDocument(APP_URI_FIELD, new BsonString(appURI)))
            .add(new BsonDocument(APP_NAME_FIELD, new BsonString(appURI)));

        var conditions = arrayBuilder()
            .add(new BsonDocument("$or", uriOrNameCond.build()))
            .add(new BsonDocument(APP_ENABLED_FIELD, new BsonBoolean(true)));

        var findArg = new BsonDocument("$and", conditions.build());

        var appDefinition = mongoClient.getDatabase(appDB).getCollection(appCollection, BsonDocument.class).find(findArg).first();

        if (appDefinition != null) {
            return GraphQLAppDeserializer.fromBsonDocument(appDefinition);
        } else {
            return null;
        }
    }
}
