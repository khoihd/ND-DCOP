package behavior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import agent.AgentPDDCOP;
import jade.core.AID;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class MGM_SEND_RECEIVE_VALUE extends OneShotBehaviour implements MESSAGE_TYPE {

  /**
   * 
   */
  private static final long serialVersionUID = -9079921323844342119L;
  
  private AgentPDDCOP agent;
  
  private int timeStep;
  
  public MGM_SEND_RECEIVE_VALUE(AgentPDDCOP agent, int timeStep) {
    super(agent);
    this.agent = agent;
    this.timeStep = timeStep;
  }
  
  @Override
  public void action() {
    for (AID neighborAgentAID : agent.getNeighborAIDSet()) {
      agent.sendObjectMessageWithTime(neighborAgentAID, agent.getChosenValueAtEachTSMap().get(timeStep),
          MGM_VALUE, agent.getSimulatedTime());
    }
    
    List<ACLMessage> receivedMessageFromNeighborList = waitingForMessageFromNeighborWithTime(MGM_VALUE);
        
    agent.startSimulatedTiming();
    
    for (ACLMessage receivedMessage : receivedMessageFromNeighborList) {
      String sender = receivedMessage.getSender().getLocalName();
      String valueFromThisNeighbor = null;
      try {
        valueFromThisNeighbor = (String) receivedMessage.getContentObject();
      } catch (UnreadableException e) {
        e.printStackTrace();
      }

      agent.getAgentViewEachTimeStepMap().computeIfAbsent(sender, k-> new HashMap<>()).put(timeStep, valueFromThisNeighbor);
    }
    
    agent.stopStimulatedTiming();
  }
  
  private List<ACLMessage> waitingForMessageFromNeighborWithTime(int msgCode) {
    List<ACLMessage> messageList = new ArrayList<ACLMessage>();

    while (messageList.size() < agent.getNeighborStrSet().size()) {
      agent.startSimulatedTiming();
      
      MessageTemplate template = MessageTemplate.MatchPerformative(msgCode);
      ACLMessage receivedMessage = myAgent.receive(template);
        
      agent.stopStimulatedTiming();
      if (receivedMessage != null) {
        long timeFromReceiveMessage = Long.parseLong(receivedMessage.getLanguage());
          
        if (timeFromReceiveMessage > agent.getSimulatedTime()) {
          agent.setSimulatedTime(timeFromReceiveMessage);
        }
        
        messageList.add(receivedMessage); 
      }
      else {
          block();
      }
    }
    
    agent.addupSimulatedTime(AgentPDDCOP.getDelayMessageTime());
    return messageList;
  }
}
