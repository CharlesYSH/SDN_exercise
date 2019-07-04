/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.st.unicastdhcp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.basics.BasicElementConfig;


/**
 * My Config class.
 */
public class MyConfig extends Config<ApplicationId>{

    // The JSON file should contain one field "name".
    public static final String MY_NAME = "name";
    public static final String DHCP_MAC = "mac";
    public static final String DHCP_CPoint = "deviceConnectPoint";

    // For ONOS to check whether an uploaded configuration is valid.
    @Override
    public boolean isValid(){
        boolean vmac = get(DHCP_MAC, null).matches("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");
        return hasOnlyFields(MY_NAME, DHCP_MAC, DHCP_CPoint) & vmac;
    }

    // To retreat the value.
    public String myname(){
        String name = get(MY_NAME, null);
        return name;
    }

    // To retreat the value.
    public String getMAC(){
        String dhcpMAC = get(DHCP_MAC, null);
        return dhcpMAC;
    }

    // To retreat the value.
    public String getCPoint(){
        String dhcpCP = get(DHCP_CPoint, null);
        return dhcpCP;
    }

    // To set or clear the value.
    public BasicElementConfig myname(String name){
        return (BasicElementConfig) setOrClear(MY_NAME, name);
    }
}
