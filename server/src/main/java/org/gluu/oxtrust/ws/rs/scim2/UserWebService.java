/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2015, Gluu
 */
package org.gluu.oxtrust.ws.rs.scim2;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import com.wordnik.swagger.annotations.Authorization;
import org.gluu.oxtrust.ldap.service.IPersonService;
import org.gluu.oxtrust.model.GluuCustomPerson;
import org.gluu.oxtrust.model.exception.SCIMException;
import org.gluu.oxtrust.model.scim2.*;
import org.gluu.oxtrust.model.scim2.patch.PatchOperation;
import org.gluu.oxtrust.model.scim2.patch.PatchRequest;
import org.gluu.oxtrust.model.scim2.util.ScimResourceUtil;
import org.gluu.oxtrust.service.scim2.Scim2PatchService;
import org.gluu.oxtrust.service.scim2.interceptor.ScimAuthorization;
import org.gluu.oxtrust.service.scim2.interceptor.RefAdjusted;
import org.gluu.oxtrust.model.scim2.user.UserResource;
import org.gluu.oxtrust.service.scim2.Scim2UserService;
import org.joda.time.format.ISODateTimeFormat;
import org.xdi.ldap.model.SortOrder;
import org.xdi.ldap.model.VirtualListViewResponse;
import org.xdi.util.Pair;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.management.InvalidAttributeValueException;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

import static org.gluu.oxtrust.model.scim2.Constants.*;

/**
 * Implementation of /Users endpoint. Methods here are intercepted and/or decorated.
 * Class org.gluu.oxtrust.service.scim2.interceptor.UserWebServiceDecorator is used to apply pre-validations on data.
 * Filter org.gluu.oxtrust.service.scim2.interceptor.AuthorizationProcessingFilter secures invocations
 *
 * @author Rahat Ali Date: 05.08.2015
 * Updated by jgomer on 2017-09-12.
 */
@Named
@Path("/scim/v2/Users")
@Api(value = "/v2/Users", description = "SCIM 2.0 User Endpoint (https://tools.ietf.org/html/rfc7644#section-3.2)",
        authorizations = {@Authorization(value = "Authorization", type = "uma")})
public class UserWebService extends BaseScimWebService implements IUserWebService {

    @Inject
    private IPersonService personService;

    @Inject
    private Scim2UserService scim2UserService;

    @Inject
    private Scim2PatchService scim2PatchService;

    /**
     *
     */
    @POST
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @RefAdjusted
    @ApiOperation(value = "Create user", notes = "https://tools.ietf.org/html/rfc7644#section-3.3", response = UserResource.class)
    public Response createUser(
            @ApiParam(value = "User", required = true) UserResource user,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList){

        Response response;
        try {
            log.debug("Executing web service method. createUser");
            GluuCustomPerson person = scim2UserService.createUser(user, endpointUrl);

            // For custom script: create user
            if (externalScimService.isEnabled())
                externalScimService.executeScimCreateUserMethods(person);

            String json=resourceSerializer.serialize(user, attrsList, excludedAttrsList);
            response=Response.created(new URI(user.getMeta().getLocation())).entity(json).build();
        }
        catch (Exception e){
            log.error("Failure at createUser method", e);
            response=getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @Path("{id}")
    @GET
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @RefAdjusted
    @ApiOperation(value = "Find user by id", notes = "Returns a user by id as path param (https://tools.ietf.org/html/rfc7644#section-3.4.1)",
            response = UserResource.class)
    public Response getUserById(
            @PathParam("id") String id,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList) {

        Response response;
        try {
            log.debug("Executing web service method. getUserById");
            UserResource user=new UserResource();
            GluuCustomPerson person=personService.getPersonByInum(id);  //person is not null (check associated decorator method)
            scim2UserService.transferAttributesToUserResource(person, user, endpointUrl);

            String json=resourceSerializer.serialize(user, attrsList, excludedAttrsList);
            response=Response.ok(new URI(user.getMeta().getLocation())).entity(json).build();
        }
        catch (Exception e){
            log.error("Failure at getUserById method", e);
            response=getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    /**
     * This implementation differs from spec in the following aspects:
     * - Passing a null value for an attribute, does not modify the attribute in the destination, however passing an
     * empty array for a multivalued attribute does clear the attribute. Thus, to clear single-valued attribute, PATCH
     * operation should be used
     */
    @Path("{id}")
    @PUT
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @RefAdjusted
    @ApiOperation(value = "Update user", notes = "Update user (https://tools.ietf.org/html/rfc7644#section-3.5.1)", response = UserResource.class)
    public Response updateUser(
            @ApiParam(value = "User", required = true) UserResource user,
            @PathParam("id") String id,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList) {

        Response response;
        try {
            log.debug("Executing web service method. updateUser");
            Pair<GluuCustomPerson, UserResource> pair=scim2UserService.updateUser(id, user, endpointUrl);

            // For custom script: update user
            if (externalScimService.isEnabled()) {
                externalScimService.executeScimUpdateUserMethods(pair.getFirst());
            }

            UserResource updatedResource=pair.getSecond();
            String json=resourceSerializer.serialize(updatedResource, attrsList, excludedAttrsList);
            response=Response.ok(new URI(updatedResource.getMeta().getLocation())).entity(json).build();
        }
        catch (InvalidAttributeValueException e){
            log.error(e.getMessage());
            response=getErrorResponse(Response.Status.CONFLICT, ErrorScimType.MUTABILITY, e.getMessage());
        }
        catch (Exception e){
            log.error("Failure at updateUser method", e);
            response=getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @Path("{id}")
    @DELETE
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @ApiOperation(value = "Delete User", notes = "Delete User (https://tools.ietf.org/html/rfc7644#section-3.6)")
    public Response deleteUser(@PathParam("id") String id){

        Response response;
        try {
            log.debug("Executing web service method. deleteUser");
            GluuCustomPerson person=personService.getPersonByInum(id);  //person cannot be null (check associated decorator method)

            // For custom script: delete user. Execute before actual deletion
            if (externalScimService.isEnabled()) {
                externalScimService.executeScimDeleteUserMethods(person);
            }

            scim2UserService.deleteUser(person);
            response=Response.noContent().build();
        }
        catch (Exception e){
            log.error("Failure at deleteUser method", e);
            response=getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @GET
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @RefAdjusted
    @ApiOperation(value = "Search users", notes = "Returns a list of users (https://tools.ietf.org/html/rfc7644#section-3.4.2.2)", response = ListResponse.class)
    public Response searchUsers(
            @QueryParam(QUERY_PARAM_FILTER) String filter,
            @QueryParam(QUERY_PARAM_START_INDEX) Integer startIndex,
            @QueryParam(QUERY_PARAM_COUNT) Integer count,
            @QueryParam(QUERY_PARAM_SORT_BY) String sortBy,
            @QueryParam(QUERY_PARAM_SORT_ORDER) String sortOrder,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList){

        Response response;
        try {
            log.debug("Executing web service method. searchUsers");

            VirtualListViewResponse vlv = new VirtualListViewResponse();
            List<BaseScimResource> resources = scim2UserService.searchUsers(filter, sortBy, SortOrder.getByValue(sortOrder),
                    startIndex, count, vlv, endpointUrl, getMaxCount());

            String json = getListResponseSerialized(vlv.getTotalResults(), startIndex, resources, attrsList, excludedAttrsList, count==0);
            response=Response.ok(json).location(new URI(endpointUrl)).build();
        }
        catch (SCIMException e){
            log.error(e.getMessage(), e);
            response=getErrorResponse(Response.Status.BAD_REQUEST, ErrorScimType.INVALID_FILTER, e.getMessage());
        }
        catch (Exception e){
            log.error("Failure at searchUsers method", e);
            response=getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @Path(SEARCH_SUFFIX)
    @POST
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @RefAdjusted
    @ApiOperation(value = "Search users POST /.search", notes = "Returns a list of users (https://tools.ietf.org/html/rfc7644#section-3.4.3)", response = ListResponse.class)
    public Response searchUsersPost(@ApiParam(value = "SearchRequest", required = true) SearchRequest searchRequest){

        log.debug("Executing web service method. searchUsersPost");

        //Calling searchUsers here does not provoke that method's interceptor/decorator being called (only this one's)
        URI uri=null;
        Response response = searchUsers(searchRequest.getFilter(),searchRequest.getStartIndex(), searchRequest.getCount(),
                searchRequest.getSortBy(), searchRequest.getSortOrder(), searchRequest.getAttributesStr(), searchRequest.getExcludedAttributesStr());

        try {
            uri = new URI(endpointUrl + "/" + SEARCH_SUFFIX);
        }
        catch (Exception e){
            log.error(e.getMessage(), e);
        }
        return Response.fromResponse(response).location(uri).build();

    }

    @Path("{id}")
    @PATCH
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ScimAuthorization
    @RefAdjusted
    @ApiOperation(value = "PATCH operation", notes = "https://tools.ietf.org/html/rfc7644#section-3.5.2", response = UserResource.class)
    public Response patchUser(
            PatchRequest request,
            @PathParam("id") String id,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList){

        Response response;
        try{
            log.debug("Executing web service method. patchUser");
            UserResource user=new UserResource();
            GluuCustomPerson person=personService.getPersonByInum(id);  //person is not null (check associated decorator method)

            //Fill user instance with all info from person
            scim2UserService.transferAttributesToUserResource(person, user, endpointUrl);

            //Apply patches one by one in sequence
            for (PatchOperation po : request.getOperations())
                user=(UserResource) scim2PatchService.applyPatchOperation(user, po);

            //Throws exception if final representation does not pass overall validation
            log.debug("patchUser. Revising final resource representation still passes validations");
            executeDefaultValidation(user);
            ScimResourceUtil.adjustPrimarySubAttributes(user);

            //Update timestamp
            String now=ISODateTimeFormat.dateTime().withZoneUTC().print(System.currentTimeMillis());
            user.getMeta().setLastModified(now);

            //Replaces the information found in person with the contents of user
            scim2UserService.replacePersonInfo(person, user);

            // For custom script: update user
            if (externalScimService.isEnabled()) {
                externalScimService.executeScimUpdateUserMethods(person);
            }

            String json=resourceSerializer.serialize(user, attrsList, excludedAttrsList);
            response=Response.ok(new URI(user.getMeta().getLocation())).entity(json).build();
        }
        catch (InvalidAttributeValueException e){
            log.error(e.getMessage(), e);
            response=getErrorResponse(Response.Status.BAD_REQUEST, ErrorScimType.MUTABILITY, e.getMessage());
        }
        catch (SCIMException e){
            response=getErrorResponse(Response.Status.BAD_REQUEST, ErrorScimType.INVALID_SYNTAX, e.getMessage());
        }
        catch (Exception e){
            log.error("Failure at patchUser method", e);
            response=getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @PostConstruct
    public void setup(){
        //Do not use getClass() here... a typical weld issue...
        endpointUrl=appConfiguration.getBaseEndpoint() + UserWebService.class.getAnnotation(Path.class).value();
    }

}
