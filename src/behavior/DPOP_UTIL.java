package behavior;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.HashSet;

import agent.AgentPDDCOP;
import agent.AgentPDDCOP.DcopAlgorithm;
import agent.AgentPDDCOP.DynamicType;
import table.Row;
import table.Table;
import utilities.Utilities;


/*
 * This is UTIL phrase of DTREE
 * 1. If X is leaf THEN
 *      WE ASSUME EACH VALUE OF PARENT HAS AT LEAST ONE CORRESPONDING VALUES FROM CHILDREN 
 * 		FOR EACH value from domain(parent)
 * 			Calculate the minimum utility constraint (for each corresponding value of children)
 * 			, then store the minimum pair (parent, children)
 * 		Then combine all the parent_value, utility
 * 		Send this vector to the parent
 * 		STOP;
 * 
 * 2. ELSE (not a leaf)
 * 		Wait until receiving all messages from all the children
 * 		2.1 If X is a root THEN
 * 			FOR EACH value of X
 * 				sum the utility that received from all the children
 * 			pick the value with the minimum utility from all the children.
 * 			STOP;
 * 
 * 		2.2 X is not a root
 * 			FOR EACH value of X
 * 				sum the utility that received from all the children
 * 			So here, we have each pair of value of X, and corresponding utility for this subtree
 * 			FOR EACH value of parent X
 * 				Calculate the minimum utility BASED ON the SUM of (corresponding constraints, and
 * 															utility from this value of X constraints)
 * 				Store this pair of (parent_value, children_value, utility)
 * 			Combine all the value of (parent_value, utility) and send to the parent
 * 			STOP;  
 */
public class DPOP_UTIL extends OneShotBehaviour implements MESSAGE_TYPE {

	private static final long serialVersionUID = -2438558665331658059L;

	AgentPDDCOP agent;
	
	private int currentTimeStep;
	
	private List<Table> dpopTableList = new ArrayList<>();
	
	public DPOP_UTIL(AgentPDDCOP agent, int currentTimeStep) {
		super(agent);
		this.agent = agent;
		this.currentTimeStep = currentTimeStep;
	}
	
	@Override
	public void action() {	  
	  // Compute the corresponding table list for DPOP to run
	  dpopTableList.addAll(computeDpopAndSwitchingCostTables(agent.getDynamicType(), agent.getAlgorithm(), currentTimeStep));
	  
		agent.setCurrentStartTime(agent.getBean().getCurrentThreadUserTime());
		agent.setCurrentUTILstartTime(System.currentTimeMillis());
		
		agent.setSimulatedTime(agent.getSimulatedTime() + agent.getBean().getCurrentThreadUserTime() - agent.getCurrentStartTime());
//		if (agent.isRoot()) System.out.println(agent.getAgentID() + ": I am root");
//		else if (agent.isLeaf()) System.out.println(agent.getAgentID() + ": I am leaf, my parent is " + agent.getParentAID().getLocalName());
//		else {System.out.println(agent.getAgentID() + ": I am internal node, my parent is " + agent.getParentAID().getLocalName());}
		
		if (agent.isLeaf()) {
			leafDoUtilProcess();
			System.out.println("Leaf is done");
		} 
		else if (agent.isRoot()){
			rootDoUtilProcess();
			//TODO: Write to file for each algorithm
			if (agent.isRunningAlgorithm(DcopAlgorithm.REACT) || agent.isRunningAlgorithm(DcopAlgorithm.HYBRID)) {
				writeTimeToFile();
			}
			if (agent.isRunningAlgorithm(DcopAlgorithm.FORWARD) || agent.isRunningAlgorithm(DcopAlgorithm.BACKWARD)) {
				if (currentTimeStep == agent.getHorizon()) {
					Utilities.writeUtil_Time_FW_BW(agent);
				}
			}
		}
		else {
			internalNodeDoUtilProcess();
			System.out.println("Internal node is done");
		}
	}		
	
	private void leafDoUtilProcess() {
		agent.setCurrentStartTime(agent.getBean().getCurrentThreadUserTime());
		
		Table combinedTable = dpopTableList.get(0);
		System.out.println(combinedTable);
		//joining other tables with table 0
		int currentTableListDPOPsize = dpopTableList.size();
		for (int index = 1; index<currentTableListDPOPsize; index++) {
			Table pseudoParentTable = dpopTableList.get(index);
			System.out.println(pseudoParentTable);
			combinedTable = joinTable(combinedTable, pseudoParentTable);
		}
		
		agent.setAgentViewTable(combinedTable);
		Table projectedTable = projectOperator(combinedTable, agent.getLocalName());

		agent.setSimulatedTime(agent.getSimulatedTime()
					+ agent.getBean().getCurrentThreadUserTime() - agent.getCurrentStartTime());
		
		agent.sendObjectMessageWithTime(agent.getParentAID(), projectedTable, DPOP_UTIL, agent.getSimulatedTime());
	}
	
	private void internalNodeDoUtilProcess() {			
		List<ACLMessage> receivedUTILmsgList = waitingForMessageFromChildrenWithTime(DPOP_UTIL);
			
		agent.setCurrentStartTime(agent.getBean().getCurrentThreadUserTime());
		
		Table combinedUtilAndConstraintTable = combineMessage(receivedUTILmsgList);

		for (Table pseudoParentTable : dpopTableList) {
			combinedUtilAndConstraintTable = joinTable(combinedUtilAndConstraintTable, pseudoParentTable);
		}

		agent.setAgentViewTable(combinedUtilAndConstraintTable);

		Table projectedTable = projectOperator(combinedUtilAndConstraintTable, agent.getLocalName());
		
		agent.setSimulatedTime(agent.getSimulatedTime() +
					agent.getBean().getCurrentThreadUserTime() - agent.getCurrentStartTime());

		agent.sendObjectMessageWithTime(agent.getParentAID(), projectedTable, DPOP_UTIL, agent.getSimulatedTime());
	}
	
	private void rootDoUtilProcess() {
		List<ACLMessage> receivedUTILmsgList = waitingForMessageFromChildrenWithTime(DPOP_UTIL);
		
		agent.setCurrentStartTime(agent.getBean().getCurrentThreadUserTime());

		Table combinedUtilAndConstraintTable = combineMessage(receivedUTILmsgList);
		for (Table pseudoParentTable : dpopTableList) {
			combinedUtilAndConstraintTable = joinTable(combinedUtilAndConstraintTable, pseudoParentTable);
		}
		
		//pick value with smallest utility
		//since agent 0 is always at the beginning of the row formatted: agent0,agent1,..,agentN -> utility
		double maxUtility = Integer.MIN_VALUE;
		
		System.err.println("Timestep " +  currentTimeStep + " Combined messages at root:");
		
		System.out.println(combinedUtilAndConstraintTable);
		
		for (Row row : combinedUtilAndConstraintTable.getRowList()) {
			if (row.getUtility() > maxUtility) {
				maxUtility = row.getUtility();
				agent.setChosenValue(row.getValueAtPosition(0));
			}
		}
			
		System.out.println("CHOSEN: " + agent.getChosenValue());
		
		//TODO: review this code for calculating utility
//		if (algorithm == C_DPOP) {
//			if (solveStableState)
//				valueAtEachTSMap.put(stableTimeStep,chosenValue);
//			totalGlobalUtility = maxUtility;
//			System.out.println("C_DPOP - utility of this DCOP is " + maxUtility);
//		}
		//correct
		if (agent.isRunningAlgorithm(DcopAlgorithm.LS_SDPOP)) {
			//new code to recalculate 
			
			agent.getChosenValueAtEachTSMap().put(currentTimeStep, agent.getChosenValue());
			agent.setTotalGlobalUtility(agent.getTotalGlobalUtility() + maxUtility);
			System.err.println("Agent " + agent.getAgentID() + " at time step "
							+ currentTimeStep + " value: "+ agent.getChosenValue());
			System.err.println("LS_SDPOP at time step " + currentTimeStep + " utility: " + maxUtility);
			System.err.println("LS_SDPOP current aggregated utility: " + agent.getTotalGlobalUtility());
		}
		else if (agent.isRunningAlgorithm(DcopAlgorithm.HYBRID)) {
			agent.getChosenValueAtEachTSMap().put(currentTimeStep, agent.getChosenValue());
		}
		else if (agent.isRunningAlgorithm(DcopAlgorithm.REACT)) {
			agent.getChosenValueAtEachTSMap().put(currentTimeStep, agent.getChosenValue());
			agent.setTotalGlobalUtility(agent.getTotalGlobalUtility() + maxUtility);
			System.err.println("Agent " + agent.getAgentID() + " at time step "
							+ currentTimeStep + " value: "+ agent.getChosenValue());
			System.err.println("REACT at time step " + currentTimeStep + " utility: " + maxUtility);
			System.err.println("REACT current aggregated utility: " + agent.getTotalGlobalUtility());
		}
		//TODO: to be completed
		else if (agent.isRunningAlgorithm(DcopAlgorithm.SDPOP)) {
//			agent.getValueAtEachTSMap().put(currentTimeStep, agent.getChosenValue());
//			agent.setTotalGlobalUtility(agent.getTotalGlobalUtility() + maxUtility);
//			System.err.println("Agent " + agent.getIdStr() + " at time step "
//							+ currentTimeStep + " value: "+ agent.getChosenValue());
//			System.err.println("SDPOP at time step " + currentTimeStep + " utility: " + maxUtility);
//			System.err.println("SDPOP current aggregated utility: " + agent.getTotalGlobalUtility());
		}
		//correct
		else if (agent.isRunningAlgorithm(DcopAlgorithm.FORWARD)) {
			agent.getChosenValueAtEachTSMap().put(currentTimeStep, agent.getChosenValue());

			agent.setTotalGlobalUtility(agent.getTotalGlobalUtility() + maxUtility);
			
			System.err.println("Agent " + agent.getAgentID() + " at time step "
							+ currentTimeStep + " value: "+ agent.getChosenValue());
			System.err.println("FORWARD_DPOP at time step " + currentTimeStep 
								+ " utility: " + maxUtility);
			System.err.println("FORWARD_DPOP current aggregated utility: " + agent.getTotalGlobalUtility());
		}
		
		else if (agent.isRunningAlgorithm(DcopAlgorithm.BACKWARD)) {
			agent.getChosenValueAtEachTSMap().put(agent.getHorizon() - currentTimeStep, 
										agent.getChosenValue());
			agent.setTotalGlobalUtility(agent.getTotalGlobalUtility() + maxUtility);
			
			System.err.println("Agent " + agent.getAgentID() + " at time step " + 
					(agent.getHorizon() - currentTimeStep) + " value: "+ agent.getChosenValue());
			System.err.println("BACKWARD_DPOP at time step " + 
					(agent.getHorizon() - currentTimeStep) + " utility: " + maxUtility);
			System.err.println("BACKWARD_DPOP current aggregated utility " + agent.getTotalGlobalUtility());
		}
		else if (agent.isRunningAlgorithm(DcopAlgorithm.C_DPOP)) {
			System.err.println("C_DPOP utility " + maxUtility);
		}
		//end of Todo
		
		agent.setSimulatedTime(agent.getSimulatedTime() + agent.getBean().getCurrentThreadUserTime()
							- agent.getCurrentStartTime());
	}
	
	private List<Table> computeDpopAndSwitchingCostTables(DynamicType dynamicType, DcopAlgorithm algorithm, int timeStep) {
    List<Table> tableList = new ArrayList<>();
    if (dynamicType == DynamicType.INFINITE_HORIZON) {
      // Solve for the last DCOP
      if (currentTimeStep == agent.getHorizon()) {
        dpopTableList.addAll(agent.computeDiscountedDecisionTableList(agent.getDpopDecisionTableList(), agent.getHorizon(), agent.getDiscountFactor()));
        dpopTableList.addAll(agent.computeDiscountedExpectedRandomTableList(agent.getDpopRandomTableList(), agent.getHorizon(), agent.getDiscountFactor()));      
      }
      else {
        // Compute the switching cost constraint
        // Collapse DCOPs from t = 0 to t = horizon -  1
        int lastTimeStep = agent.getHorizon() - 1;
        if (algorithm == DcopAlgorithm.C_DPOP) {
          dpopTableList.addAll(computeCollapsedDecisionTableList(agent.getDpopDecisionTableList(), lastTimeStep, agent.getDiscountFactor()));
          dpopTableList.addAll(computeCollapsedDecisionTableList(agent.getDpopRandomTableList(), lastTimeStep, agent.getDiscountFactor()));
          dpopTableList.add(computeCollapsedSwitchingCostTable(agent.getAgentID(), agent.getSelfDomain(), lastTimeStep, agent.getDiscountFactor()));
        }
        else if (algorithm == DcopAlgorithm.FORWARD) {
          dpopTableList.addAll(agent.computeDiscountedDecisionTableList(agent.getDpopDecisionTableList(), currentTimeStep, agent.getDiscountFactor()));
          dpopTableList.addAll(agent.computeDiscountedExpectedRandomTableList(agent.getDpopRandomTableList(), currentTimeStep, agent.getDiscountFactor()));
          if (currentTimeStep > 0) {
            Table switchingCostToPreviousSolution = switchingCostGivenSolution(agent.getAgentID(), agent.getDecisionVariableDomainMap().get(agent.getAgentID()), agent.getChosenValueAtEachTSMap().get(currentTimeStep - 1)); 
            dpopTableList.add(switchingCostToPreviousSolution);
          }
          if (currentTimeStep == lastTimeStep) {
            Table switchingCostToLastSolution = switchingCostGivenSolution(agent.getAgentID(), agent.getDecisionVariableDomainMap().get(agent.getAgentID()), agent.getChosenValueAtEachTSMap().get(agent.getHorizon())); 
            dpopTableList.add(switchingCostToLastSolution);
          }
        }
        else if (algorithm == DcopAlgorithm.BACKWARD) {
          dpopTableList.addAll(agent.computeDiscountedDecisionTableList(agent.getDpopDecisionTableList(), currentTimeStep, agent.getDiscountFactor()));
          dpopTableList.addAll(agent.computeDiscountedExpectedRandomTableList(agent.getDpopRandomTableList(), currentTimeStep, agent.getDiscountFactor()));
          // If not at the horizon, add switching cost regarding the solution at timeStep + 1
          if (currentTimeStep < agent.getHorizon()) {
            Table switchingCostToLaterSolution = switchingCostGivenSolution(agent.getAgentID(), agent.getDecisionVariableDomainMap().get(agent.getAgentID()), agent.getChosenValueAtEachTSMap().get(currentTimeStep + 1)); 
            dpopTableList.add(switchingCostToLaterSolution);
          }
        }
        else if (algorithm == DcopAlgorithm.LS_SDPOP) {
          dpopTableList.addAll(agent.computeDiscountedDecisionTableList(agent.getDpopDecisionTableList(), currentTimeStep, agent.getDiscountFactor()));
          dpopTableList.addAll(agent.computeDiscountedExpectedRandomTableList(agent.getDpopRandomTableList(), currentTimeStep, agent.getDiscountFactor()));
        }
      }
    }
    else if (dynamicType == DynamicType.FINITE_HORIZON) {
      if (algorithm == DcopAlgorithm.C_DPOP) {
          dpopTableList.addAll(computeCollapsedDecisionTableList(agent.getDpopDecisionTableList(), agent.getHorizon(), agent.getDiscountFactor()));
          dpopTableList.addAll(computeCollapsedRandomTableList(agent.getDpopRandomTableList(), agent.getHorizon(), agent.getDiscountFactor()));
      }
      else {
        dpopTableList.addAll(agent.computeDiscountedDecisionTableList(agent.getDpopDecisionTableList(), currentTimeStep, agent.getDiscountFactor()));
        dpopTableList.addAll(agent.computeDiscountedExpectedRandomTableList(agent.getDpopRandomTableList(), currentTimeStep, agent.getDiscountFactor()));
        if (algorithm == DcopAlgorithm.FORWARD) {
          if (currentTimeStep > 0) {
            dpopTableList.add(switchingCostGivenSolution(agent.getAgentID(), agent.getDecisionVariableDomainMap().get(agent.getAgentID()), agent.getChosenValueAtEachTSMap().get(currentTimeStep - 1))); 
          }
        }
        else if (algorithm == DcopAlgorithm.BACKWARD) {
          if (currentTimeStep < agent.getHorizon()) {
            dpopTableList.add(switchingCostGivenSolution(agent.getAgentID(), agent.getDecisionVariableDomainMap().get(agent.getAgentID()), agent.getChosenValueAtEachTSMap().get(currentTimeStep + 1))); 
          }
        }
      }
    }
    
    return tableList;
	}

  private List<ACLMessage> waitingForMessageFromChildren(int msgCode) {
		List<ACLMessage> messageList = new ArrayList<ACLMessage>();
		while (messageList.size() < agent.getChildrenAIDList().size()) {
			MessageTemplate template = MessageTemplate.MatchPerformative(msgCode);
			ACLMessage receivedMessage = myAgent.receive(template);
			if (receivedMessage != null) {
				messageList.add(receivedMessage);
			}
			else
				block();
		}
		return messageList;
	}
	
	private List<ACLMessage> waitingFromRandChildren(int msgCode, int noRandChild) {
		List<ACLMessage> messageList = new ArrayList<ACLMessage>();
		while (messageList.size() < noRandChild) {
			MessageTemplate template = MessageTemplate.MatchPerformative(msgCode);
			ACLMessage receivedMessage = myAgent.receive(template);
			if (receivedMessage != null) {
				messageList.add(receivedMessage);
			}
			else
				block();
		}
		return messageList;
	}
	
	private List<ACLMessage> waitingForMessageFromChildrenWithTime(int msgCode) {
		List<ACLMessage> messageList = new ArrayList<ACLMessage>();

		while (messageList.size() < agent.getChildrenAIDList().size()) {
			MessageTemplate template = MessageTemplate.MatchPerformative(msgCode);
			ACLMessage receivedMessage = myAgent.receive(template);
			
			if (receivedMessage != null) {
				long timeFromReceiveMessage = Long.parseLong(receivedMessage.getLanguage());
				
				if (timeFromReceiveMessage > agent.getSimulatedTime()) {
					agent.setSimulatedTime(timeFromReceiveMessage);
				}
				messageList.add(receivedMessage);	
			}
			else
				block();
		}
		agent.setSimulatedTime(agent.getSimulatedTime() + AgentPDDCOP.getDelayMessageTime());
		return messageList;
	}
	
	//create indexList1
	//la vi tri cua tung variable (tu nho den lon)
	//o trong row1, ma nam trong commonVariables
	
	//create indexList2
	//la vi tri cua tung variable (tu nho den lon)
	//o trong row2, ma nam trong commonVariables
	
	//join(label1, label2) as the same order
	
	//for each row1 of table1.getTable()
	//	for each row2 of table2.getTable()
	//		join(row1, row2, indexList1, indexList2)
	private Table joinTable(Table table1, Table table2) {
		//get commonVariables
		List<String> commonVariables = getCommonVariables(table1.getDecVarLabel(), table2.getDecVarLabel());
		
		//create indexList1, indexList2
		//xet tung variable commonVariables
		//add index of that variable to the indexList
		List<Integer> indexContainedInCommonList1 = new ArrayList<Integer>();
		List<Integer> indexContainedInCommonList2 = new ArrayList<Integer>();
		for (String variable:commonVariables) {
			indexContainedInCommonList1.add(table1.getDecVarLabel().indexOf(variable));
			indexContainedInCommonList2.add(table2.getDecVarLabel().indexOf(variable));
		}
		//create returnTable
		//join label
		List<String> joinedLabelTable1FirstThenTable2 = getJoinLabel(table1.getDecVarLabel(), table2.getDecVarLabel()
																			,indexContainedInCommonList2);
		
		Table joinedTable = new Table(joinedLabelTable1FirstThenTable2);
		for (Row row1:table1.getRowList()) {
			for (Row row2:table2.getRowList()) {
				Row joinedRow = getJoinRow(row1, row2, indexContainedInCommonList1, 
						indexContainedInCommonList2);
				if (joinedRow != null)
					joinedTable.addRow(joinedRow);
			}
		}
		
		return joinedTable;
	}
	
	private List<String> getCommonVariables(List<String> variableList1, List<String> variableList2) {
		List<String> commonVariableList = new ArrayList<String>();
		
		for (String variable1:variableList1)
			for (String variable2:variableList2) {
				//check if equal agents, and if list contains agent or not
				//if (variable1.equals(variable2) && isContainVariable(commonVariableList, variable1) == false)
				if (variable1.equals(variable2) && !commonVariableList.contains(variable1)) {	
					commonVariableList.add(variable1);
					break;
				}
			}
		
		return commonVariableList;
	}
	
	//for variable1 from label1, add to joinedLabel
	//for variable2 from label2
	//	if index not in indexContainedInCommonList2
	//	then add to joinedLabel
	public List<String> getJoinLabel(List<String> label1, List<String> label2,
											List<Integer> indexContainedInCommonList2) {
												
	  List<String> joinedLabel = new ArrayList<String>();// (label1);
		for (String variable1:label1) {
			joinedLabel.add(variable1);
		}
		
		//add variable with index not in indexContainedInCommonList2
		for (int i=0; i<label2.size(); i++) {
			if (!indexContainedInCommonList2.contains(i))
				joinedLabel.add(label2.get(i));
		}
	
		return joinedLabel;
	}
	
	public Row getJoinRow(Row row1, Row row2, List<Integer> indexList1, 
			  List<Integer> indexList2) {
		//check if same size
		if (indexList1.size() != indexList2.size()) {
			System.err.println("Different size from indexList: " + indexList1.size() +
										 " " + indexList2.size());
			return null;
		}
		
		int listSize = indexList1.size();
		//check if same values
		for (int i=0; i<listSize; i++) {
			if (row1.getValueList().get(indexList1.get(i)).equals
			(row2.getValueList().get(indexList2.get(i))) == false) {
				//System.out.println("Different values here!");
				return null;
			}
		}
		
		//join two row
		List<String> joinedValues = new ArrayList<String>();//(row1.getValueList());
		for (String value1:row1.getValueList()) {
			joinedValues.add(value1);
		}
		
		for (int i=0; i<row2.getValueList().size(); i++) {
			if (indexList2.contains(i) == false)
				joinedValues.add(row2.getValueList().get(i));
		}
		
		Row joinedRow = new Row(joinedValues, row1.getUtility() + row2.getUtility());
		return joinedRow;				
	}
	
	//create new TabelDPOP
	//create new Label: eliminate variableToProject
	//create new Table with -1 dimension
	//create checkedList mark already picked tuples
	//for each tuple1 from the table
	//	if index(tuple1) already in picked tuple => continue
	//	for each tuple2:tuple1->end from the table
	//		compare to the minimum , and update
	//	add to new Table
	public Table projectOperator(Table table, String variableToProject) {
		int indexEliminated = getIndexOfContainedVariable(table.getDecVarLabel(), variableToProject);
		if (indexEliminated == -1) {
			return null;
		}
		
		//create arrayIndex
		List<Integer> arrayIndex = new ArrayList<Integer>();
		for (int i=0; i<table.getDecVarLabel().size(); i++) {
			if (i != indexEliminated)
				arrayIndex.add(i);
		}
		
		//create checkedList
		List<Integer> checkedList = new ArrayList<Integer>();
		
		//create projectedLabel
		List<String> projectedLabel = createTupleFromList(table.getDecVarLabel(), arrayIndex);
		
		//create projectedTable
		Table projectTable = new Table(projectedLabel);
		for (int i=0; i<table.getRowCount(); i++) {
			if (checkedList.contains(i) == true)
				continue;
			checkedList.add(i);
			Row row1 = table.getRowList().get(i);
			List<String> tuple1 = createTupleFromRow(row1, arrayIndex);
			double maxUtility = row1.getUtility();
			List<String> maxTuple = tuple1;
			
			for (int j=i+1; j<table.getRowCount(); j++) {
				Row row2 = table.getRowList().get(j);
				List<String> tuple2 = createTupleFromRow(row2, arrayIndex);
				double row2Utility = row2.getUtility();
				if (isSameTuple(tuple1, tuple2) == true) {
					checkedList.add(j);
					if (row2Utility > maxUtility) {
						maxUtility = row2Utility;
						maxTuple = tuple2;
					}
				}
				
			}
			
			projectTable.addRow(new Row(maxTuple, maxUtility));
		}
		
		return projectTable;
	}
	
	int getIndexOfContainedVariable(List<String> list, String input) {
		return list.indexOf(input);
	}
	
	//create tuples from Row and arrayIndex
	public List<String> createTupleFromList(List<String> list, List<Integer> arrayIndex) {
		if (arrayIndex.size() >= list.size()) {
//			System.err.println("Cannot create tuple with size: " + arrayIndex + " from Row size: " +
//									list.size());
			return null;
		}
		List<String> newTuple = new ArrayList<String>();
		for (Integer index:arrayIndex) {
			newTuple.add(list.get(index));
		}
		return newTuple;
	}
	
	//create tuples from Row and arrayIndex
	public List<String> createTupleFromRow(Row row, List<Integer> arrayIndex) {
		if (arrayIndex.size() >= row.getVariableCount()) {
//			System.err.println("Cannot create tuple with size: " + arrayIndex + " from Row size: " +
//									row.variableCount);
			return null;
		}
		List<String> newTuple = new ArrayList<String>();
		for (Integer index:arrayIndex) {
			newTuple.add(row.getValueAtPosition(index));
		}
		return newTuple;
	}
	
	//check if two tuples has the same values
	public boolean isSameTuple(List<String> tuple1, List<String> tuple2) {
		if (tuple1.size() != tuple2.size()) {
			System.err.println("Different size from two tuples: " + tuple1.size() + " and "
																	+ tuple2.size());
			return false;
		}
		int size = tuple1.size();
		for (int i=0; i<size; i++) {
			if (tuple1.get(i).equals(tuple2.get(i)) == false) {
				return false;
			}
		}
		return true;
	}
	
	//for each value of X
	//for each message received from the children
	//sum the utility that received from the children
	Table combineMessage(List<ACLMessage> list) {
		List<Table> listTable = new ArrayList<Table>();
		for (ACLMessage msg:list) {
			try {
				listTable.add((Table) msg.getContentObject());
			} catch (UnreadableException e) {
				e.printStackTrace();
			}
		}
		
		int size = listTable.size();
		Table table = listTable.get(0);

		for (int i=1; i<size; i++) {
			table = joinTable(table, listTable.get(i));
		}

		return table;
	}
	
	public void writeTimeToFile() {
		if (!agent.isRunningAlgorithm(DcopAlgorithm.REACT)) {return;}
		
		String line = "";
//		String alg = DCOP_INFO.algTypes[agent.algorithm];
		String alg = String.valueOf(agent.getAlgorithm());
		if (currentTimeStep == 0)
			line = line + alg + "\t" + agent.getInputFileName() + "\n";
		
		DecimalFormat df = new DecimalFormat("##.##");
		df.setRoundingMode(RoundingMode.DOWN);
		long runTime = System.currentTimeMillis() - agent.getCurrentUTILstartTime();
		
		line = line + "ts=" + currentTimeStep + "\t" + df.format(runTime) + " ms" + "\n";

		String fileName = "id=" + agent.getInstanceID() + "/sw=" + (int) agent.getSwitchingCost() + "/react_runtime.txt";
		byte data[] = line.getBytes();
	    Path p = Paths.get(fileName);

	    try (OutputStream out = new BufferedOutputStream(
	      Files.newOutputStream(p, CREATE, APPEND))) {
	      out.write(data, 0, data.length);
	    } catch (IOException x) {
	      System.err.println(x);
	    }
	}
  
  /**
   * @param decisionTableList
   * @param lastTimeStep
   * @param discountFactor
   * @return
   */
  private List<Table> computeCollapsedDecisionTableList(List<Table> decisionTableList, int lastTimeStep, double discountFactor) {
    List<Table> collapedDecistionTableList = new ArrayList<>();
    for (Table decTable : decisionTableList) {
      List<Table> similarTableList = new ArrayList<>();

      for (int timeStep = 0; timeStep <= lastTimeStep; timeStep++) {
        similarTableList.add(agent.computeDiscountedDecisionTable(decTable, timeStep, discountFactor));
      }
      
      collapedDecistionTableList.add(computeCollapsedTableFromList(similarTableList));
    }
    
    return collapedDecistionTableList;
  }
  
  private List<Table> computeCollapsedRandomTableList(List<Table> randomTableList, int lastTimeStep, double discountFactor) {
    List<Table> collapedDecistionTableList = new ArrayList<>();
    for (Table randomTable : randomTableList) {
      List<Table> similarTableList = new ArrayList<>();

      for (int timeStep = 0; timeStep <= lastTimeStep; timeStep++) {
        if (agent.isDynamic(DynamicType.FINITE_HORIZON) && timeStep == agent.getHorizon()) {
          similarTableList.add(computeLongtermExpectedTable(randomTable, discountFactor));
        } 
        else {
          similarTableList.add(agent.computeDiscountedExpectedTable(randomTable, timeStep, discountFactor));
        }
      }
      
      collapedDecistionTableList.add(computeCollapsedTableFromList(similarTableList));
    }
    
    return collapedDecistionTableList;
  }
 
 private List<Table> computeLongtermExpectedTableList(List<Table> randomTableList, double discountFactor) {
   List<Table> tableList = new ArrayList<>();
   for (Table table : randomTableList) {
     tableList.add(computeLongtermExpectedTable(table, discountFactor));
   }
   
   return tableList;
 }
 
 // they have the same entry, only different utility
 private Table computeCollapsedTableFromList(List<Table> tableList) {
   if (tableList.size() == 0) {
     return null;
   }
   Table joinedTable = new Table(tableList.get(0).getDecVarLabel());
   int variableCount = tableList.get(0).getDecVarLabel().size();
   int rowCount = tableList.get(0).getRowCount();
   int tableCount = tableList.size();
   int totalSize = (int) Math.pow(rowCount, tableCount);

   for (int count = 0; count < totalSize; count++) {
     List<String> valueTuple = new ArrayList<String>(variableCount);
     for (int i = 0; i < variableCount; i++) {
       valueTuple.add("");
     }
     double sumUtility = 0;
     int quotient = count;
     // for each table count, decide the index of each column, then add to the tuple
     for (int tableIndex = tableCount - 1; tableIndex >= 0; tableIndex--) {
       int remainder = quotient % rowCount;
       quotient = quotient / rowCount;
       Row row = tableList.get(tableIndex).getRowList().get(remainder);
       sumUtility += row.getUtility();
       List<String> valueList = row.getValueList();
       for (int idx = 0; idx < valueList.size(); idx++) {
         valueTuple.set(idx, valueList.get(idx) + "," + valueTuple.get(idx));
       }
     }
     for (int idx = 0; idx < valueTuple.size(); idx++) {
       valueTuple.set(idx, valueTuple.get(idx).substring(0, valueTuple.get(idx).length() - 1));
     }
     joinedTable.addRow(new Row(valueTuple, sumUtility));
   }

   return joinedTable;
 }
 
 private double computeSwitchingCost(List<String> valueTuple, double discountFactor) {
   double scost = 0;
   
   for (int index = 0; index < valueTuple.size() - 1; index++) {
     scost += Math.pow(discountFactor, index) * agent.switchingCostFunction(valueTuple.get(index), valueTuple.get(index + 1));
   }
   
   return scost;
 }
 
 /**
  * Compute unary switching cost table of given agent agent given valueList and differentValue
  * @param valueList
  * @param differentValue
  * @return
  */
 private Table switchingCostGivenSolution(String agentIdentifier, List<String> valueList, String differentValue) {
   List<String> label = new ArrayList<>();
   label.add(agentIdentifier);
   Table unarySwitchingCostTable = new Table(label);
   
   for (String value : valueList) {
     List<String> tableValueList = new ArrayList<>();
     tableValueList.add(value);
     Row row = new Row(tableValueList, agent.switchingCostFunction(value, differentValue));
     
     unarySwitchingCostTable.addRow(row);
   }
   
   return unarySwitchingCostTable;
 }
 
  /**
   * Compute unary switching cost table given domain and a number of time step
   * @param domain
   * @param timeStep
   * @return
   */
  private Table computeCollapsedSwitchingCostTable(String id, List<String> domain, int timeStep, double df) {
    List<String> label = new ArrayList<>();
    
    label.add(id);
    
    Table collapsedSwitchingCostTable = new Table(label);
    
    List<Set<String>> domainSetList = new ArrayList<Set<String>>();
    for (int timeIndex = 0; timeIndex <= timeStep; timeIndex++) {
      domainSetList.add(new HashSet<>(domain));
    }
    Set<List<String>> productValues = Sets.cartesianProduct(domainSetList);
    for (List<String> valueList : productValues) {
      double sc = 0;
      String valueListString = "";
      for (int i = 0; i < valueList.size(); i++) {
        if (i < valueList.size() - 1) {
          sc += agent.switchingCostFunction(valueList.get(i), valueList.get(i + 1));
        }
        valueListString += valueList.get(i) + ",";
      }
      valueListString = valueListString.substring(0, valueListString.length() - 1);
      List<String> finalValue = new ArrayList<>();
      finalValue.add(valueListString);
      
      collapsedSwitchingCostTable.addRow(new Row(finalValue, sc)); 
    }
    
    return collapsedSwitchingCostTable;
  }
  
  private Table computeLongtermExpectedTable(Table randomTable, double discountFactor) {
    Table newlyCreatedTable;
    List<List<String>> processedDecValues = new ArrayList<List<String>>();

    List<String> decVarLabel = randomTable.getDecVarLabel();
    List<String> randVarLabel = randomTable.getRandVarLabel();
    List<List<String>> allTupleValue = agent.getAllTupleValueOfGivenLabel(randVarLabel, false);
    newlyCreatedTable = new Table(decVarLabel, randVarLabel);

    int noOfEquations = 1;
    for (String randVar : randVarLabel) {
      noOfEquations *= agent.getRandomVariableDomainMap().get(randVar).size();
    }

    // traverse each row of tableWithRandom
    for (Row rowToBeTraversed : randomTable.getRowList()) {
      List<String> decValueList = rowToBeTraversed.getValueList();

      // check if decValueList contained in processedDecValues
      if (processedDecValues.contains(decValueList)) {
        continue;
      } // search for all values of random Variable
      else {
        processedDecValues.add(decValueList);
        /**
         * construct unknown equations, by add coefficients
         *
         * construct coefficients (1-delta*prob[0,0] - delta*prob[0,1] ... -
         * delta*prob[0,n] = v0 -delta*prob[1,0] + (1-delta*prob[1,1] ...-
         * delta*prob[1,n] = v1
         *
         * -delta*prob[n,0] - delta*prob[n,1] ... + (1-delta*prob[n,n] = v_n
         **/
        double coefficients[][] = new double[noOfEquations][noOfEquations + 1];
        // select rowTuple => colTuple
        for (int row = 0; row < noOfEquations; row++) {
          List<String> rowTuple = allTupleValue.get(row);
          // get colTuple
          for (int column = 0; column < noOfEquations; column++) {
            List<String> colTuple = allTupleValue.get(column);
            double transProb = 1;
            for (int randIndex = 0; randIndex < randVarLabel.size(); randIndex++) {
              String randVar = randVarLabel.get(randIndex);
              transProb = transProb
                  * agent.getTransitionFunctionMap().get(randVar).getProbByValue(rowTuple.get(randIndex), colTuple.get(randIndex));
            }

            if (row == column)
              coefficients[row][column] = 1 - discountFactor * transProb;
            else
              coefficients[row][column] = -discountFactor * transProb;

          }
          // set utility
          coefficients[row][noOfEquations] = agent.getUtilityFromTableGivenDecAndRand(randomTable, decValueList, rowTuple);
        }

        // System.out.println(Arrays.deepToString(coefficients));
        List<Double> utilityList = gaussian(coefficients, noOfEquations);

        // create new row with a fix dec values, but different rand values
        int i = 0;
        for (List<String> randValueToBeAddedList : allTupleValue) {
          Row newRow = new Row(decValueList, randValueToBeAddedList, utilityList.get(i));
          i++;
          newlyCreatedTable.addRow(newRow);
        }
        // end if: decValueList not contained
      }
      // end while: traversing table
    }
    return newlyCreatedTable;
  }
  
  private List<Double> gaussian(double arr[][], int N) {
    List<Double> longtermUtilityList = new ArrayList<Double>();
    // take each line as pivot, except for the last line
    for (int pivotIndex = 0; pivotIndex < N - 1; pivotIndex++) {
      // go from the line below line pivotIndex, to the last line
      boolean isNotZeroRowFound = false;
      if (arr[pivotIndex][pivotIndex] == 0) {
        int notZeroRow;
        for (notZeroRow = pivotIndex + 1; notZeroRow < N; notZeroRow++) {
          if (arr[notZeroRow][pivotIndex] != 0) {
            isNotZeroRowFound = true;
            break;
          }
        }

        if (isNotZeroRowFound) {
          // swap row pivotIndex and row notZeroRow
          for (int columnToSwapIndex = 0; columnToSwapIndex < N + 1; columnToSwapIndex++) {
            double tempForSwap = arr[pivotIndex][columnToSwapIndex];
            arr[pivotIndex][columnToSwapIndex] = arr[notZeroRow][columnToSwapIndex];
            arr[notZeroRow][columnToSwapIndex] = tempForSwap;
          }
        } else {
          continue;
        }
      }

      for (int rowForGauss = pivotIndex + 1; rowForGauss < N; rowForGauss++) {
        double factor = arr[rowForGauss][pivotIndex] / arr[pivotIndex][pivotIndex];
        for (int columnForGauss = 0; columnForGauss < N + 1; columnForGauss++) {
          arr[rowForGauss][columnForGauss] = arr[rowForGauss][columnForGauss]
              - factor * arr[pivotIndex][columnForGauss];
        }
      }
    }

    for (int columnPivot = N - 1; columnPivot >= 1; columnPivot--) {
      for (int rowAbovePivot = columnPivot - 1; rowAbovePivot >= 0; rowAbovePivot--) {
        double fraction = arr[rowAbovePivot][columnPivot] / arr[columnPivot][columnPivot];
        for (int columnInTheRow = 0; columnInTheRow < N + 1; columnInTheRow++)
          arr[rowAbovePivot][columnInTheRow] = arr[rowAbovePivot][columnInTheRow]
              - fraction * arr[columnPivot][columnInTheRow];
      }
    }

    for (int i = 0; i < N; i++) {
      longtermUtilityList.add(arr[i][N] / arr[i][i]);
    }

    return longtermUtilityList;
  }
}