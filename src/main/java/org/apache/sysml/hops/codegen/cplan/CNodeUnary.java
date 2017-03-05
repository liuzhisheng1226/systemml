/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.hops.codegen.cplan;

import java.util.Arrays;

import org.apache.sysml.parser.Expression.DataType;


public class CNodeUnary extends CNode
{
	public enum UnaryType {
		ROW_SUMS, LOOKUP_R, LOOKUP_RC, LOOKUP0, //codegen specific
		EXP, POW2, MULT2, SQRT, LOG,
		ABS, ROUND, CEIL, FLOOR, SIGN, 
		SIN, COS, TAN, ASIN, ACOS, ATAN,
		SELP, SPROP, SIGMOID, LOG_NZ; 
		
		public static boolean contains(String value) {
			for( UnaryType ut : values()  )
				if( ut.name().equals(value) )
					return true;
			return false;
		}
		
		public String getTemplate(boolean sparse) {
			switch( this ) {
				case ROW_SUMS:
					return sparse ? "    double %TMP% = LibSpoofPrimitives.vectSum( %IN1v%, %IN1i%, %POS1%, %LEN%);\n": 
									"    double %TMP% = LibSpoofPrimitives.vectSum( %IN1%, %POS1%,  %LEN%);\n"; 
				case EXP:
					return "    double %TMP% = FastMath.exp(%IN1%);\n";
			    case LOOKUP_R:
					return "    double %TMP% = %IN1%[rowIndex];\n";
			    case LOOKUP_RC:
					return "    double %TMP% = %IN1%[rowIndex*n+colIndex];\n";	
				case LOOKUP0:
					return "    double %TMP% = %IN1%[0];\n" ;
				case POW2:
					return "    double %TMP% = %IN1% * %IN1%;\n" ;
				case MULT2:
					return "    double %TMP% = %IN1% + %IN1%;\n" ;
				case ABS:
					return "    double %TMP% = Math.abs(%IN1%);\n";
				case SIN:
					return "    double %TMP% = Math.sin(%IN1%);\n";
				case COS: 
					return "    double %TMP% = Math.cos(%IN1%);\n";
				case TAN:
					return "    double %TMP% = Math.tan(%IN1%);\n";
				case ASIN:
					return "    double %TMP% = Math.asin(%IN1%);\n";
				case ACOS:
					return "    double %TMP% = Math.acos(%IN1%);\n";
				case ATAN:
					return "    double %TMP% = Math.atan(%IN1%);\n";
				case SIGN:
					return "    double %TMP% = Math.signum(%IN1%);\n";
				case SQRT:
					return "    double %TMP% = Math.sqrt(%IN1%);\n";
				case LOG:
					return "    double %TMP% = FastMath.log(%IN1%);\n";
				case ROUND: 
					return "    double %TMP% = Math.round(%IN1%);\n";
				case CEIL:
					return "    double %TMP% = Math.ceil(%IN1%);\n";
				case FLOOR:
					return "    double %TMP% = Math.floor(%IN1%);\n";
				case SELP:
					return "    double %TMP% = (%IN1%>0) ? %IN1% : 0;\n";
				case SPROP:
					return "    double %TMP% = %IN1% * (1 - %IN1%);\n";
				case SIGMOID:
					return "    double %TMP% = 1 / (1 + FastMath.exp(-%IN1%));\n";
				case LOG_NZ:
					return "    double %TMP% = (%IN1%==0) ? 0 : FastMath.log(%IN1%);\n";
					
				default: 
					throw new RuntimeException("Invalid unary type: "+this.toString());
			}
		}
	}
	
	private UnaryType _type;
	
	public CNodeUnary( CNode in1, UnaryType type ) {
		_inputs.add(in1);
		_type = type;
		setOutputDims();
	}
	
	public UnaryType getType() {
		return _type;
	}
	
	public void setType(UnaryType type) {
		_type = type;
	}

	@Override
	public String codegen(boolean sparse) {
		if( _generated )
			return "";
			
		StringBuilder sb = new StringBuilder();
		
		//generate children
		sb.append(_inputs.get(0).codegen(sparse));
		
		//generate binary operation
		String var = createVarname();
		String tmp = _type.getTemplate(sparse);
		tmp = tmp.replaceAll("%TMP%", var);
		
		String varj = _inputs.get(0).getVarname();
		if( sparse && !tmp.contains("%IN1%") ) {
			tmp = tmp.replaceAll("%IN1v%", varj+"vals");
			tmp = tmp.replaceAll("%IN1i%", varj+"ix");
		}
		else
			tmp = tmp.replaceAll("%IN1%", varj );
		
		if(varj.startsWith("b")  ) //i.e. b.get(index)
		{
			tmp = tmp.replaceAll("%POS1%", "bi");
			tmp = tmp.replaceAll("%POS2%", "bi");
		}
		tmp = tmp.replaceAll("%POS1%", varj+"i");
		tmp = tmp.replaceAll("%POS2%", varj+"i");
		
		sb.append(tmp);
		
		//mark as generated
		_generated = true;
		
		return sb.toString();
	}
	
	@Override
	public String toString() {
		switch(_type) {
			case ROW_SUMS: return "u(R+)";
			default:
				return super.toString();
		}
	}

	@Override
	public void setOutputDims() {
		switch(_type) {
			case ROW_SUMS:
			case EXP:
			case LOOKUP_R:
			case LOOKUP_RC:
			case LOOKUP0:	
			case POW2:
			case MULT2:	
			case ABS:  
			case SIN:
			case COS: 
			case TAN:
			case ASIN:
			case ACOS:
			case ATAN:
			case SIGN:
			case SQRT:
			case LOG:
			case ROUND: 
			case CEIL:
			case FLOOR:
			case SELP:	
			case SPROP:
			case SIGMOID:
			case LOG_NZ:
				_rows = 0;
				_cols = 0;
				_dataType= DataType.SCALAR;
				break;
			default:
				throw new RuntimeException("Operation " + _type.toString() + " has no "
					+ "output dimensions, dimensions needs to be specified for the CNode " );
		}
		
	}
	
	@Override
	public int hashCode() {
		if( _hash == 0 ) {
			int h1 = super.hashCode();
			int h2 = _type.hashCode();
			_hash = Arrays.hashCode(new int[]{h1,h2});
		}
		return _hash;
	}
	
	@Override 
	public boolean equals(Object o) {
		if( !(o instanceof CNodeUnary) )
			return false;
		
		CNodeUnary that = (CNodeUnary) o;
		return super.equals(that)
			&& _type == that._type;
	}
}
