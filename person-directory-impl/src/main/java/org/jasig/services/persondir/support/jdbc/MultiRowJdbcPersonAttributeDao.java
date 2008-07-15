/* Copyright 2004 The JA-SIG Collaborative.  All rights reserved.
*  See license distributed with this file and
*  available online at http://www.uportal.org/license.html
*/

package org.jasig.services.persondir.support.jdbc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.collections15.Factory;
import org.apache.commons.collections15.map.LazyMap;
import org.jasig.services.persondir.IPerson;
import org.jasig.services.persondir.support.MultivaluedPersonAttributeUtils;
import org.jasig.services.persondir.support.NamedPersonImpl;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.ParameterizedRowMapper;

/**
 * An {@link org.jasig.services.persondir.IPersonAttributeDao}
 * implementation that maps attribute names and values from name and value column
 * pairs. This is usefull if user attributes are stored in a table like:<br>
 * <table border="1">
 *  <tr>
 *      <th>USER_NM</th>
 *      <th>ATTR_NM</th>
 *      <th>ATTR_VL</th>
 *  </tr>
 *  <tr>
 *      <td>jstudent</td>
 *      <td>name.given</td>
 *      <td>joe</td>
 *  </tr>
 *  <tr>
 *      <td>jstudent</td>
 *      <td>name.family</td>
 *      <td>student</td>
 *  </tr>
 *  <tr>
 *      <td>badvisor</td>
 *      <td>name.given</td>
 *      <td>bob</td>
 *  </tr>
 *  <tr>
 *      <td>badvisor</td>
 *      <td>name.family</td>
 *      <td>advisor</td>
 *  </tr>
 * </table>
 * 
 * <br>
 * 
 * This class expects 1 to N row results for a query, with each row containing 1 to N name
 * value attribute mappings and the userName of the user the attributes are for. This contrasts
 * {@link org.jasig.services.persondir.support.jdbc.SingleRowJdbcPersonAttributeDao} which expects
 * a single row result for a user query. <br>
 * 
 * <br>
 * <br>
 * Configuration:
 * <table border="1">
 *     <tr>
 *         <th align="left">Property</th>
 *         <th align="left">Description</th>
 *         <th align="left">Required</th>
 *         <th align="left">Default</th>
 *     </tr>
 *     <tr>
 *         <td align="right" valign="top">nameValueColumnMappings</td>
 *         <td>
 *             A {@link Map} of attribute name columns to attribute value columns. A single result row can have multiple
 *             name columns and multiple value columns associated with each name. The values of the {@link Ma}p can be
 *             either {@link String} or {@link Collection<String>}.
 *         </td>
 *         <td valign="top">Yes</td>
 *         <td valign="top">null</td>
 *     </tr>
 * </table>
 * 
 * @author andrew.petro@yale.edu
 * @author Eric Dalquist <a href="mailto:edalquist@unicon.net">edalquist@unicon.net</a>
 * @version $Revision$ $Date$
 * @since uPortal 2.5
 */
public class MultiRowJdbcPersonAttributeDao extends AbstractJdbcPersonAttributeDao<Map<String, Object>> {
    private static final ParameterizedRowMapper<Map<String, Object>> MAPPER = new ColumnMapParameterizedRowMapper();
    
    /**
     * {@link Map} of columns from a name column to value columns.
     * Keys are Strings, Values are Strings or List of Strings 
     */
    private Map<String, Set<String>> nameValueColumnMappings = null;
    
    /**
     * Creates a new MultiRowJdbcPersonAttributeDao specifying the DataSource and SQL to use.
     * 
     * @param ds The DataSource to get connections from for executing queries, may not be null.
     * @param sql The SQL to execute for user attributes, may not be null.
     */
    public MultiRowJdbcPersonAttributeDao(DataSource ds, String sql) {
        super(ds, sql);
    }
    

    /**
     * @return The Map of name column to value column(s). 
     */
    public Map<String, Set<String>> getNameValueColumnMappings() {
        return this.nameValueColumnMappings;
    }
    
    /**
     * The {@link Map} of columns from a name column to value columns. Keys are Strings,
     * Values are Strings or {@link java.util.List} of Strings.
     * 
     * @param nameValueColumnMap The Map of name column to value column(s). 
     */
    public void setNameValueColumnMappings(final Map<String, ?> nameValueColumnMap) {
        if (nameValueColumnMap == null) {
            this.nameValueColumnMappings = null;
        }
        else {
            final Map<String, Set<String>> mappings = MultivaluedPersonAttributeUtils.parseAttributeToAttributeMapping(nameValueColumnMap);
            
            if (mappings.containsValue(null)) {
                throw new IllegalArgumentException("nameValueColumnMap may not have null values");
            }
            
            this.nameValueColumnMappings = mappings;
        }
    }


    /* (non-Javadoc)
     * @see org.jasig.services.persondir.support.jdbc.AbstractJdbcPersonAttributeDao#getRowMapper()
     */
    @Override
    protected ParameterizedRowMapper<Map<String, Object>> getRowMapper() {
        return MAPPER;
    }

    /* (non-Javadoc)
     * @see org.jasig.services.persondir.support.jdbc.AbstractJdbcPersonAttributeDao#parseAttributeMapFromResults(java.util.List)
     */
    @Override
    protected List<IPerson> parseAttributeMapFromResults(List<Map<String, Object>> queryResults) {
        final Map<String, Map<String, List<Object>>> peopleAttributesBuilder = LazyMap.decorate(new LinkedHashMap<String, Map<String, List<Object>>>(), new LinkedHashMapFactory<String, List<Object>>());

        final String userNameAttribute = this.getConfiguredUserNameAttribute();
        
        for (final Map<String, Object> queryResult : queryResults) {
            final Object userName = queryResult.get(userNameAttribute);
            
            if (userName == null) {
                throw new BadSqlGrammarException("No userName column named '" + userNameAttribute + "' exists in result set", this.getQueryTemplate(), null);
            }
            
            final Map<String, List<Object>> attributes = peopleAttributesBuilder.get(userName.toString());
            
            //Iterate over each attribute column mapping to get the data from the row
            for (final Map.Entry<String, Set<String>> columnMapping : this.nameValueColumnMappings.entrySet()) {
                final String keyColumn = columnMapping.getKey();
                
                //Get the attribute name for the specified column
                final Object attrNameObj = queryResult.get(keyColumn);
                if (attrNameObj == null && !queryResult.containsKey(keyColumn)) {
                    throw new BadSqlGrammarException("No attribute key column named '" + keyColumn + "' exists in result set", this.getQueryTemplate(), null);
                }
                final String attrName = String.valueOf(attrNameObj);
                
                //Get the columns containing the values and add all values to a List
                final Set<String> valueColumns = columnMapping.getValue();
                final List<Object> attrValues = new ArrayList<Object>(valueColumns.size());
                for (final String valueColumn : valueColumns) {
                    final Object attrValue = queryResult.get(valueColumn);
                    if (attrValue == null && !queryResult.containsKey(valueColumn)) {
                        throw new BadSqlGrammarException("No attribute value column named '" + valueColumn + "' exists in result set", this.getQueryTemplate(), null);
                    }
                    
                    attrValues.add(attrValue);
                }

                //Add the name/values to the attributes Map
                MultivaluedPersonAttributeUtils.addResult(attributes, attrName, attrValues);
            }
        }
        
        
        //Convert the builder structure into a List of IPersons
        final List<IPerson> people = new ArrayList<IPerson>(peopleAttributesBuilder.size());
        
        for (final Map.Entry<String, Map<String, List<Object>>> mappedAttributesEntry : peopleAttributesBuilder.entrySet()) {
            final String userName = mappedAttributesEntry.getKey();
            final Map<String, List<Object>> attributes = mappedAttributesEntry.getValue();
            final IPerson person = new NamedPersonImpl(userName, attributes);
            people.add(person);
        }

        return people;
    }
    
    private static final class LinkedHashMapFactory<K, V> implements Factory<Map<K, V>> {
        public Map<K, V> create() {
            return new LinkedHashMap<K, V>();
        }
    }
}