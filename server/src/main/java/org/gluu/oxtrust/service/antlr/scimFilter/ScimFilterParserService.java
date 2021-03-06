/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2017, Gluu
 */
package org.gluu.oxtrust.service.antlr.scimFilter;

import com.unboundid.ldap.sdk.Filter;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.lang.StringUtils;
import org.gluu.oxtrust.model.exception.SCIMException;
import org.gluu.oxtrust.model.scim2.BaseScimResource;
import org.gluu.oxtrust.service.antlr.scimFilter.antlr4.ScimFilterBaseListener;
import org.gluu.oxtrust.service.antlr.scimFilter.antlr4.ScimFilterLexer;
import org.gluu.oxtrust.service.antlr.scimFilter.antlr4.ScimFilterParser;
import org.gluu.oxtrust.service.antlr.scimFilter.util.FilterUtil;
import org.slf4j.Logger;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/**
 * @author Val Pecaoco
 * Re-engineered by jgomer on 2017-12-09.
 */
@Stateless
@Named
public class ScimFilterParserService {

    @Inject
    private Logger log;

    private ParseTree getParseTree(String filter, ScimFilterErrorListener errorListener){

        ANTLRInputStream input = new ANTLRInputStream(filter);
        ScimFilterLexer lexer = new ScimFilterLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        ScimFilterParser parser = new ScimFilterParser(tokens);
        parser.setTrimParseTree(true);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        return parser.filter();
    }

    public ParseTree getParseTree(String filter) throws Exception {

        ScimFilterErrorListener errorListener=new ScimFilterErrorListener();
        ParseTree tree=getParseTree(filter, errorListener);
        checkParsingErrors(errorListener);
        return tree;

    }

    private void checkParsingErrors(ScimFilterErrorListener errorListener) throws SCIMException {

        String outputErr=errorListener.getOutput();
        String symbolErr=errorListener.getSymbol();
        if (StringUtils.isNotEmpty(outputErr) || StringUtils.isNotEmpty(symbolErr))
            throw new SCIMException(String.format("Error parsing filter (symbol='%s'; message='%s')", symbolErr, outputErr));

    }

    private void walkTree(String filter, ScimFilterBaseListener listener) throws SCIMException {

        ScimFilterErrorListener errorListener=new ScimFilterErrorListener();
        ParseTree tree=getParseTree(filter, errorListener);
        checkParsingErrors(errorListener);
        ParseTreeWalker.DEFAULT.walk(listener, tree);

    }

    public Filter createLdapFilter(String filter, String defaultStr, Class<? extends BaseScimResource> clazz) throws SCIMException {

        try {
            Filter ldapFilter;

            if (StringUtils.isEmpty(filter))
                ldapFilter=Filter.create(defaultStr);
            else {
                LdapFilterListener ldapFilterListener = new LdapFilterListener(clazz);
                walkTree(FilterUtil.preprocess(filter, clazz), ldapFilterListener);
                ldapFilter = ldapFilterListener.getFilter();

                if (ldapFilter == null)
                    throw new Exception("An error occurred when building LDAP filter: " + ldapFilterListener.getError());
            }

            return ldapFilter;
        }
        catch (Exception e){
            throw new SCIMException(e.getMessage(), e);
        }

    }

    public Boolean complexAttributeMatch(ParseTree parseTree, Map<String, Object> item, String parent, Class<? extends BaseScimResource> clazz) throws Exception {

        MatchFilterVisitor matchVisitor=new MatchFilterVisitor(item, parent, clazz);
        return matchVisitor.visit(parseTree);
    }

}
