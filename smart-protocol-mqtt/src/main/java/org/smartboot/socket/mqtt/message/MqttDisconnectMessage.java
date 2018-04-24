package org.smartboot.socket.mqtt.message;

import org.smartboot.socket.mqtt.MqttFixedHeader;

/**
 * @author 三刀
 * @version V1.0 , 2018/4/22
 */
public class MqttDisconnectMessage extends MessageIdVariableHeaderMessage {
    public MqttDisconnectMessage(MqttFixedHeader mqttFixedHeader) {
        super(mqttFixedHeader);
    }
}