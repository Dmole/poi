/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.hssf.model;

import org.apache.poi.hssf.record.formula.AbstractFunctionPtg;
import org.apache.poi.hssf.record.formula.ControlPtg;
import org.apache.poi.hssf.record.formula.ValueOperatorPtg;
import org.apache.poi.hssf.record.formula.Ptg;

/**
 * This class performs 'operand class' transformation. Non-base tokens are classified into three 
 * operand classes:
 * <ul>
 * <li>reference</li> 
 * <li>value</li> 
 * <li>array</li> 
 * </ul>
 * <p/>
 * 
 * The final operand class chosen for each token depends on the formula type and the token's place
 * in the formula. If POI gets the operand class wrong, Excel <em>may</em> interpret the formula
 * incorrectly.  This condition is typically manifested as a formula cell that displays as '#VALUE!',
 * but resolves correctly when the user presses F2, enter.<p/>
 * 
 * The logic implemented here was partially inspired by the description in
 * "OpenOffice.org's Documentation of the Microsoft Excel File Format".  The model presented there
 * seems to be inconsistent with observed Excel behaviour (These differences have not been fully
 * investigated). The implementation in this class has been heavily modified in order to satisfy
 * concrete examples of how Excel performs the same logic (see TestRVA).<p/>
 * 
 * Hopefully, as additional important test cases are identified and added to the test suite, 
 * patterns might become more obvious in this code and allow for simplification.
 * 
 * @author Josh Micich
 */
final class OperandClassTransformer {

	private final int _formulaType;

	public OperandClassTransformer(int formulaType) {
		_formulaType = formulaType;
	}

	/**
	 * Traverses the supplied formula parse tree, calling <tt>Ptg.setClass()</tt> for each non-base
	 * token to set its operand class.
	 */
	public void transformFormula(ParseNode rootNode) {
		byte rootNodeOperandClass;
		switch (_formulaType) {
			case FormulaParser.FORMULA_TYPE_CELL:
				rootNodeOperandClass = Ptg.CLASS_VALUE;
				break;
			default:
				throw new RuntimeException("Incomplete code - formula type (" 
						+ _formulaType + ") not supported yet");
		
		}
		transformNode(rootNode, rootNodeOperandClass, false);
	}

	private void transformNode(ParseNode node, byte desiredOperandClass,
			boolean callerForceArrayFlag) {
		Ptg token = node.getToken();
		ParseNode[] children = node.getChildren();
		if (token instanceof ValueOperatorPtg || token instanceof ControlPtg) {
			// Value Operator Ptgs and Control are base tokens, so token will be unchanged
			
			// but any child nodes are processed according to desiredOperandClass and callerForceArrayFlag
			for (int i = 0; i < children.length; i++) {
				ParseNode child = children[i];
				transformNode(child, desiredOperandClass, callerForceArrayFlag);
			}
			return;
		}
		if (token instanceof AbstractFunctionPtg) {
			transformFunctionNode((AbstractFunctionPtg) token, children, desiredOperandClass,
					callerForceArrayFlag);
			return;
		}
		if (children.length > 0) {
			throw new IllegalStateException("Node should not have any children");
		}

		if (token.isBaseToken()) {
			// nothing to do
			return;
		}
        if (callerForceArrayFlag) {
        	switch (desiredOperandClass) {
        		case Ptg.CLASS_VALUE:
        		case Ptg.CLASS_ARRAY:
        			token.setClass(Ptg.CLASS_ARRAY); 
        			break;
        		case Ptg.CLASS_REF:
        			token.setClass(Ptg.CLASS_REF); 
        			break;
        		default:
        			throw new IllegalStateException("Unexpected operand class ("
        					+ desiredOperandClass + ")");
        	}
        } else {
        	token.setClass(desiredOperandClass);
        }
	}

	private void transformFunctionNode(AbstractFunctionPtg afp, ParseNode[] children,
			byte desiredOperandClass, boolean callerForceArrayFlag) {

		boolean localForceArrayFlag;
		byte defaultReturnOperandClass = afp.getDefaultOperandClass();

		if (callerForceArrayFlag) {
			switch (defaultReturnOperandClass) {
				case Ptg.CLASS_REF:
					if (desiredOperandClass == Ptg.CLASS_REF) {
						afp.setClass(Ptg.CLASS_REF);
					} else {
						afp.setClass(Ptg.CLASS_ARRAY);
					}
					localForceArrayFlag = false;
					break;
				case Ptg.CLASS_ARRAY:
					afp.setClass(Ptg.CLASS_ARRAY);
					localForceArrayFlag = false;
					break;
				case Ptg.CLASS_VALUE:
					afp.setClass(Ptg.CLASS_ARRAY);
					localForceArrayFlag = true;
					break;
				default:
					throw new IllegalStateException("Unexpected operand class ("
							+ defaultReturnOperandClass + ")");
			}
		} else {
			if (defaultReturnOperandClass == desiredOperandClass) {
				localForceArrayFlag = false;
				// an alternative would have been to for non-base Ptgs to set their operand class 
				// from their default, but this would require the call in many subclasses because
				// the default OC is not known until the end of the constructor
				afp.setClass(defaultReturnOperandClass); 
			} else {
				switch (desiredOperandClass) {
					case Ptg.CLASS_VALUE:
						// always OK to set functions to return 'value'
						afp.setClass(Ptg.CLASS_VALUE); 
						localForceArrayFlag = false;
						break;
					case Ptg.CLASS_ARRAY:
						switch (defaultReturnOperandClass) {
							case Ptg.CLASS_REF:
								afp.setClass(Ptg.CLASS_REF);
								break;
							case Ptg.CLASS_VALUE:
								afp.setClass(Ptg.CLASS_ARRAY);
								break;
							default:
								throw new IllegalStateException("Unexpected operand class ("
										+ defaultReturnOperandClass + ")");
						}
						localForceArrayFlag = (defaultReturnOperandClass == Ptg.CLASS_VALUE);
						break;
					case Ptg.CLASS_REF:
						switch (defaultReturnOperandClass) {
							case Ptg.CLASS_ARRAY:
								afp.setClass(Ptg.CLASS_ARRAY);
								break;
							case Ptg.CLASS_VALUE:
								afp.setClass(Ptg.CLASS_VALUE);
								break;
							default:
								throw new IllegalStateException("Unexpected operand class ("
										+ defaultReturnOperandClass + ")");
						}
						localForceArrayFlag = false;
						break;
					default:
						throw new IllegalStateException("Unexpected operand class ("
								+ desiredOperandClass + ")");
				}

			}
		}

		for (int i = 0; i < children.length; i++) {
			ParseNode child = children[i];
			byte paramOperandClass = afp.getParameterClass(i);
			transformNode(child, paramOperandClass, localForceArrayFlag);
		}
	}
}