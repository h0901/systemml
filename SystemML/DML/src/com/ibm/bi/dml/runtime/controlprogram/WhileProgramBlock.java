package com.ibm.bi.dml.runtime.controlprogram;

import java.util.ArrayList;

import com.ibm.bi.dml.parser.WhileStatementBlock;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.BooleanObject;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.CPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.ComputationCPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.CPInstruction.CPINSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.instructions.Instruction.INSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.instructions.SQLInstructions.SQLScalarAssignInstruction;
import com.ibm.bi.dml.sql.sqlcontrolprogram.ExecutionContext;
import com.ibm.bi.dml.utils.DMLRuntimeException;
import com.ibm.bi.dml.utils.DMLUnsupportedOperationException;


public class WhileProgramBlock extends ProgramBlock 
{
	private ArrayList<Instruction> _predicate;
	private String _predicateResultVar;
	private ArrayList <Instruction> _exitInstructions ;
	private ArrayList<ProgramBlock> _childBlocks;

	public WhileProgramBlock(Program prog, ArrayList<Instruction> predicate) throws DMLRuntimeException{
		super(prog);
		_predicate = predicate;
		_predicateResultVar = findPredicateResultVar ();
		_exitInstructions = new ArrayList<Instruction>();
		_childBlocks = new ArrayList<ProgramBlock>(); 
	}
	
	public void printMe() {
		
		System.out.println("***** while current block predicate inst: *****");
		for (Instruction cp : _predicate){
			cp.printMe();
		}
		
		for (ProgramBlock pb : this._childBlocks){
			pb.printMe();
		}
		
		System.out.println("***** current block inst exit: *****");
		for (Instruction i : this._exitInstructions) {
			i.printMe();
		}
	}
	
	

	
	public void addProgramBlock(ProgramBlock childBlock) {
		_childBlocks.add(childBlock);
	}
	
	public void setExitInstructions2(ArrayList<Instruction> exitInstructions)
		{ _exitInstructions = exitInstructions; }

	public void setExitInstructions1(ArrayList<Instruction> predicate)
		{ _predicate = predicate; }
	
	public void addExitInstruction(Instruction inst)
		{ _exitInstructions.add(inst); }
	
	public ArrayList<Instruction> getPredicate()
		{ return _predicate; }
	
	public String getPredicateResultVar()
		{ return _predicateResultVar; }
	
	public void setPredicateResultVar(String resultVar) 
		{ _predicateResultVar = resultVar; }
	
	public ArrayList<Instruction> getExitInstructions()
		{ return _exitInstructions; }
	
	private BooleanObject executePredicate(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException 
	{
		BooleanObject result = null;
		try
		{
			if( _predicate!=null && _predicate.size()>0 )
			{
				if( _sb!=null )
				{
					WhileStatementBlock wsb = (WhileStatementBlock)_sb;
					result = (BooleanObject) executePredicate(_predicate, wsb.getPredicateHops(), ValueType.BOOLEAN, ec);
				}
				else
					result = (BooleanObject) executePredicate(_predicate, null, ValueType.BOOLEAN, ec);
			}
			else
				result = (BooleanObject)getScalarInput(_predicateResultVar, ValueType.BOOLEAN);
		}
		catch(Exception ex)
		{
			LOG.trace("\nWhile predicate variables: "+ _variables.toString());
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the WHILE predicate.", ex);
		}
		
		if ( result == null )
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the WHILE predicate.");
		
		return result;
	}
	
	public void execute(ExecutionContext ec) throws DMLRuntimeException, DMLUnsupportedOperationException{

		BooleanObject predResult = executePredicate(ec); 
		
		while(predResult.getBooleanValue()){
				
			// for each program block
			for (int i=0; i < this._childBlocks.size(); i++){
				ProgramBlock pb = this._childBlocks.get(i);
				pb.setVariables(_variables);
				
				try {
					pb.execute(ec);
				}
				catch(Exception e){
					
					LOG.trace("\nWhile predicate variables: "+ _variables.toString());
					
					throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating child program block", e);
				}
				
				_variables = pb._variables;
			}
			predResult = executePredicate(ec);
		}
		
		try {
			executeInstructions(_exitInstructions, ec);
		}
		catch(Exception e){
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error executing exit instructions ", e);
		}
		
	}
	
	public ArrayList<ProgramBlock> getChildBlocks() {
		return _childBlocks;
	}
	
	public void setChildBlocks(ArrayList<ProgramBlock> childs) 
	{
		_childBlocks = childs;
	}
	

	private String findPredicateResultVar ( ) {
		String result = null;
		for ( Instruction si : _predicate ) {
			if ( si.getType() == INSTRUCTION_TYPE.CONTROL_PROGRAM && ((CPInstruction)si).getCPInstructionType() != CPINSTRUCTION_TYPE.Variable ) {
				result = ((ComputationCPInstruction) si).getOutputVariableName();  
			}
			else if(si instanceof SQLScalarAssignInstruction)
				result = ((SQLScalarAssignInstruction) si).getVariableName();
		}
		return result;
	}
	
	public String printBlockErrorLocation(){
		return "ERROR: Runtime error in while program block generated from while statement block between lines " + _beginLine + " and " + _endLine + " -- ";
	}
}