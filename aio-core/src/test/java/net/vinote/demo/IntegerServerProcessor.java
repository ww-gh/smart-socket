package net.vinote.demo;

import org.smartboot.socket.service.MessageProcessor;
import org.smartboot.socket.transport.AioSession;
import org.smartboot.socket.util.StateMachineEnum;

import java.io.IOException;

/**
 * @author Seer
 * @version V1.0 , 2017/8/23
 */
public class IntegerServerProcessor implements MessageProcessor<Integer> {
    @Override
    public void process(AioSession<Integer> session, Integer msg) {
        Integer respMsg=msg+1;
        System.out.println("接受到客户端数据：" + msg + " ,响应数据:" + (respMsg));
        try {
            session.write(respMsg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stateEvent(AioSession<Integer> session, StateMachineEnum stateMachineEnum) {

    }
}
