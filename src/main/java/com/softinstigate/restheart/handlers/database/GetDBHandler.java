/*
 * Copyright SoftInstigate srl. All Rights Reserved.
 *
 *
 * The copyright to the computer program(s) herein is the property of
 * SoftInstigate srl, Italy. The program(s) may be used and/or copied only
 * with the written permission of SoftInstigate srl or in accordance with the
 * terms and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied. This copyright notice must not be removed.
 */
package com.softinstigate.restheart.handlers.database;

import com.mongodb.DB;
import com.mongodb.MongoClient;
import com.softinstigate.restheart.handlers.GetHandler;
import com.softinstigate.restheart.utils.RequestContext;
import io.undertow.server.HttpServerExchange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author uji
 */
public class GetDBHandler extends GetHandler
{
    private static final Logger logger = LoggerFactory.getLogger(GetDBHandler.class);
    
    /**
     * Creates a new instance of EntityResource
     */
    public GetDBHandler()
    {
    }

    @Override
    protected String generateContent(HttpServerExchange exchange, MongoClient client, int page, int pagesize, Deque<String> sortBy, Deque<String> filterBy, Deque<String> filter)
    {
        RequestContext rc = new RequestContext(exchange);
        
        DB db = client.getDB(rc.getDBName());
        
        List<String> colls = new ArrayList(db.getCollectionNames());
        
        int size = colls.size();
        
        Collections.sort(colls); // sort by id
        
        // apply page and pagesize
        
        colls = colls.subList((page-1)*pagesize, (page-1)*pagesize + pagesize > colls.size() ? colls.size() : (page-1)*pagesize + pagesize );
        
        // apply sort_by
        
        logger.warn("sort_by not yet implemented");
        
        // apply filter_by and filter
        
        logger.warn("filter not yet implemented");
        
        List<Map<String, Object>> data = new ArrayList<>();

        colls.stream().map(
                (coll) ->
                {
                    TreeMap<String, Object> properties = new TreeMap<>();

                    properties.put("_id", coll);
                    return properties;
                }
        ).forEach((item) -> { data.add(item); });
        
        return generateCollectionContent(exchange.getRequestURL(), exchange.getQueryString(), data, page, pagesize, size, sortBy, filterBy, filter);
    }
}