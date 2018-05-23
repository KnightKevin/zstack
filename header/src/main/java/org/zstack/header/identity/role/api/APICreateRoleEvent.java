package org.zstack.header.identity.role.api;

import org.zstack.header.identity.role.RoleInventory;
import org.zstack.header.identity.role.RoleState;
import org.zstack.header.identity.role.RoleType;
import org.zstack.header.message.APIEvent;
import org.zstack.header.rest.RestResponse;

import static org.codehaus.groovy.runtime.InvokerHelper.asList;

@RestResponse(allTo = "inventory")
public class APICreateRoleEvent extends APIEvent {
    private RoleInventory inventory;

    public APICreateRoleEvent() {
    }

    public APICreateRoleEvent(String apiId) {
        super(apiId);
    }

    public RoleInventory getInventory() {
        return inventory;
    }

    public void setInventory(RoleInventory inventory) {
        this.inventory = inventory;
    }


    public static APICreateRoleEvent __example__() {
        APICreateRoleEvent event = new APICreateRoleEvent();

        RoleInventory role = new RoleInventory();
        role.setName("role-1");
        role.setStatements(asList("statement for test"));
        role.setDescription("role for test");
        role.setUuid(uuid());
        role.setState(RoleState.Enabled);
        role.setType(RoleType.Customized);

        event.setInventory(role);

        return event;
    }
}
