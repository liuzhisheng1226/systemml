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

package org.apache.sysml.runtime.codegen;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.functionobjects.KahanPlus;
import org.apache.sysml.runtime.instructions.cp.DoubleObject;
import org.apache.sysml.runtime.instructions.cp.KahanObject;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.SparseBlock;
import org.apache.sysml.runtime.util.UtilFunctions;

public abstract class SpoofCellwise extends SpoofOperator implements Serializable
{
	private static final long serialVersionUID = 3442528770573293590L;
	private static final long PAR_NUMCELL_THRESHOLD = 1024*1024;   //Min 1M elements
	
	public enum CellType {
		NO_AGG,
		FULL_AGG,
		ROW_AGG,
	}
	
	protected CellType _type = CellType.NO_AGG;
	
	public SpoofCellwise() {

	}
	
	public CellType getCellType() {
		return _type;
	}
	
	@Override
	public ScalarObject execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects, int k) 
		throws DMLRuntimeException 
	{
		//sanity check
		if( inputs==null || inputs.size() < 1  )
			throw new RuntimeException("Invalid input arguments.");
		
		if( inputs.get(0).getNumRows()*inputs.get(0).getNumColumns()<PAR_NUMCELL_THRESHOLD ) {
			k = 1; //serial execution
		}
		
		//input preparation
		double[][] b = prepInputMatrices(inputs);
		double[] scalars = prepInputScalars(scalarObjects);
		
		final int m = inputs.get(0).getNumRows();
		final int n = inputs.get(0).getNumColumns();	
		double sum = 0;
		if( k <= 1 ) //SINGLE-THREADED
		{
			sum = ( !inputs.get(0).isInSparseFormat() ) ?
				executeDenseAndAgg(inputs.get(0).getDenseBlock(), b, scalars, n, m, 0, m) :
				executeSparseAndAgg(inputs.get(0).getSparseBlock(), b, scalars, n, m, 0, m);
		}
		else  //MULTI-THREADED
		{
			try {
				ExecutorService pool = Executors.newFixedThreadPool( k );
				ArrayList<ParAggTask> tasks = new ArrayList<ParAggTask>();
				int nk = UtilFunctions.roundToNext(Math.min(8*k,m/32), k);
				int blklen = (int)(Math.ceil((double)m/nk));
				for( int i=0; i<nk & i*blklen<m; i++ )
					tasks.add(new ParAggTask(inputs.get(0), b, scalars, n, m,i*blklen, Math.min((i+1)*blklen, m))); 
				//execute tasks
				List<Future<Double>> taskret = pool.invokeAll(tasks);	
				pool.shutdown();
			
				//aggregate partial results
				KahanObject kbuff = new KahanObject(0, 0);
				KahanPlus kplus = KahanPlus.getKahanPlusFnObject();
				for( Future<Double> task : taskret )
					kplus.execute2(kbuff, task.get());
				sum = kbuff._sum;
			}
			catch(Exception ex) {
				throw new DMLRuntimeException(ex);
			}
		}
		return new DoubleObject(sum);
	}

	@Override
	public void execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects, MatrixBlock out) 
		throws DMLRuntimeException
	{
		execute(inputs, scalarObjects, out, 1);
	}
	
	@Override
	public void execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects, MatrixBlock out, int k)	
		throws DMLRuntimeException
	{
		//sanity check
		if( inputs==null || inputs.size() < 1 || out==null )
			throw new RuntimeException("Invalid input arguments.");
		
		if( inputs.get(0).getNumRows()*inputs.get(0).getNumColumns()<PAR_NUMCELL_THRESHOLD ) {
			k = 1; //serial execution
		}
		
		//result allocation and preparations
		out.reset(inputs.get(0).getNumRows(), _type == CellType.NO_AGG ? 
				inputs.get(0).getNumColumns() : 1, false);
		out.allocateDenseBlock();
		double[] c = out.getDenseBlock();
		
		//input preparation
		double[][] b = prepInputMatrices(inputs);
		double[] scalars = prepInputScalars(scalarObjects);
		
		//core sequential execute
		final int m = inputs.get(0).getNumRows();
		final int n = inputs.get(0).getNumColumns();		
		
		long lnnz = 0;
		if( k <= 1 ) //SINGLE-THREADED
		{
			lnnz = (!inputs.get(0).isInSparseFormat()) ?
				executeDense(inputs.get(0).getDenseBlock(), b, scalars, c, n, m, 0, m) :
				executeSparse(inputs.get(0).getSparseBlock(), b, scalars, c, n, m, 0, m);
		}
		else  //MULTI-THREADED
		{
			try {
				ExecutorService pool = Executors.newFixedThreadPool( k );
				ArrayList<ParExecTask> tasks = new ArrayList<ParExecTask>();
				int nk = UtilFunctions.roundToNext(Math.min(8*k,m/32), k);
				int blklen = (int)(Math.ceil((double)m/nk));
				for( int i=0; i<nk & i*blklen<m; i++ )
					tasks.add(new ParExecTask(inputs.get(0), b, scalars, c, 
						n, m, i*blklen, Math.min((i+1)*blklen, m))); 
				//execute tasks
				List<Future<Long>> taskret = pool.invokeAll(tasks);	
				pool.shutdown();
				
				//aggregate nnz and error handling
				for( Future<Long> task : taskret )
					lnnz += task.get();
			}
			catch(Exception ex) {
				throw new DMLRuntimeException(ex);
			}
		}
		
		//post-processing
		out.setNonZeros(lnnz);	
		out.examSparsity();	
	}
	
	/**
	 * 
	 * @param a
	 * @param b
	 * @param c
	 * @param n
	 * @param rl
	 * @param ru
	 */
	private double executeDenseAndAgg(double[] a, double[][] b, double[] scalars, int n, int m, int rl, int ru) 
	{
		KahanObject kbuff = new KahanObject(0, 0);
		KahanPlus kplus = KahanPlus.getKahanPlusFnObject();

		if( a == null ) { //empty
			//note: we can't determine sparse-safeness by executing the operator once 
			//as the output might change with different row indices
			for( int i=rl; i<ru; i++ ) 
				for( int j=0; j<n; j++ )
					kplus.execute2(kbuff, genexecDense( 0, b, scalars, n, m, i, j ));
		}
		else { //general case
			for( int i=rl, ix=rl*n; i<ru; i++ ) 
				for( int j=0; j<n; j++, ix++ )
					kplus.execute2(kbuff, genexecDense( a[ix], b, scalars, n, m, i, j ));
		}
		
		return kbuff._sum;
	}
	
	private long executeDense(double[] a, double[][] b, double[] scalars, double[] c, int n, int m, int rl, int ru) 
	{
		long lnnz = 0;
		
		if( _type == CellType.NO_AGG )
		{
			if( a == null ) { //empty
				//note: we can't determine sparse-safeness by executing the operator once 
				//as the output might change with different row indices
				for( int i=rl, ix=rl*n; i<ru; i++ ) 
					for( int j=0; j<n; j++, ix++ ) {
						c[ix] = genexecDense( 0, b, scalars, n, m, i, j ); 
						lnnz += (c[ix]!=0) ? 1 : 0;
					}
			}
			else { //general case
				for( int i=rl, ix=rl*n; i<ru; i++ ) 
					for( int j=0; j<n; j++, ix++ ) {
						c[ix] = genexecDense( a[ix], b, scalars, n, m, i, j); 
						lnnz += (c[ix]!=0) ? 1 : 0;
					}
			}
		}
		else if( _type == CellType.ROW_AGG )
		{
			KahanObject kbuff = new KahanObject(0, 0);
			KahanPlus kplus = KahanPlus.getKahanPlusFnObject();

			if( a == null ) { //empty
				//note: we can't determine sparse-safeness by executing the operator once 
				//as the output might change with different row indices
				for( int i=rl; i<ru; i++ ) { 
					kbuff.set(0, 0);
					for( int j=0; j<n; j++ )
						kplus.execute2(kbuff, genexecDense( 0, b, scalars, n, m, i, j ));
					c[i] = kbuff._sum;
					lnnz += (c[i]!=0) ? 1 : 0;
				}
			}
			else { //general case
				for( int i=rl, ix=rl*n; i<ru; i++ ) {
					kbuff.set(0, 0);
					for( int j=0; j<n; j++, ix++ )
						kplus.execute2(kbuff, genexecDense( a[ix], b, scalars, n, m, i, j ));
					c[i] = kbuff._sum;
					lnnz += (c[i]!=0) ? 1 : 0;
				}
			}
		}
		
		return lnnz;
	}
	
	private double executeSparseAndAgg(SparseBlock sblock, double[][] b, double[] scalars, int n, int m, int rl, int ru) 
	{
		KahanObject kbuff = new KahanObject(0, 0);
		KahanPlus kplus = KahanPlus.getKahanPlusFnObject();
		
		//TODO rework sparse safe test
		double val = genexecDense( 0, b, scalars, n, m, 0, 0 );
		
		if(val == 0 && b.length==0) // sparse safe
		{
			if( sblock != null ) {
				for( int i=rl; i<ru; i++ )
					if( !sblock.isEmpty(i) ) {
						int apos = sblock.pos(i);
						int alen = sblock.size(i);
						double[] avals = sblock.values(i);
						for( int j=apos; j<apos+alen; j++ ) {
							kplus.execute2( kbuff, genexecDense(avals[j], b, scalars, n, m, i, j)); 
						}
					}	
			}
		}
		else //sparse-unsafe
		{
			for(int i=rl; i<ru; i++)
				for(int j=0; j<n; j++) {
					double valij = (sblock != null) ? sblock.get(i, j) : 0;
					kplus.execute2( kbuff, genexecDense(valij, b, scalars, n, m, i, j)); 
				}
		}
		
		return kbuff._sum;
	}
	
	private long executeSparse(SparseBlock sblock, double[][] b, double[] scalars, double[] c, int n, int m, int rl, int ru) 
	{
		//TODO rework sparse safe test
		double val0 = genexecDense( 0, b, scalars, n, m, 0, 0 );
		long lnnz = 0;
		
		if( _type == CellType.NO_AGG )
		{
			if(val0 == 0 && b.length == 0) // sparse safe
			{
				if( sblock != null ) {
					for( int i=rl; i<ru; i++ )
						if( !sblock.isEmpty(i) ) {
							int apos = sblock.pos(i);
							int alen = sblock.size(i);
							double[] avals = sblock.values(i);
							for( int j=apos; j<apos+alen; j++ ) {
								double val = genexecDense(avals[j], b, scalars, n, m, i, j);
								c[i*n+sblock.indexes(i)[j]] = val;
								lnnz += (val!=0) ? 1 : 0;
							}
						}
				}
			}
			else //sparse-unsafe
			{
				for(int i=rl, cix=rl*n; i<ru; i++, cix+=n)
					for(int j=0; j<n; j++) {
						double valij = (sblock != null) ? sblock.get(i, j) : 0;
						c[cix+j] = genexecDense(valij, b, scalars, n, m, i, j); 
						lnnz += (c[cix+j]!=0) ? 1 : 0;
					}
			}
		}
		else if( _type == CellType.ROW_AGG ) 
		{
			KahanObject kbuff = new KahanObject(0, 0);
			KahanPlus kplus = KahanPlus.getKahanPlusFnObject();

			if(val0 == 0 && b.length == 0) // sparse safe
			{
				if( sblock != null ) {
					for( int i=rl; i<ru; i++ ) {
						if( sblock.isEmpty(i) ) continue;
						kbuff.set(0, 0);
						int apos = sblock.pos(i);
						int alen = sblock.size(i);
						double[] avals = sblock.values(i);
						for( int j=apos; j<apos+alen; j++ ) {
							kplus.execute2(kbuff, genexecDense(avals[j], b, scalars, n, m, i, j));
						}
						c[i] = kbuff._sum; 
						lnnz += (c[i]!=0) ? 1 : 0;	
					}
				}
			}
			else //sparse-unsafe
			{
				for(int i=rl; i<ru; i++) {
					kbuff.set(0, 0);
					for(int j=0; j<n; j++) {
						double valij = (sblock != null) ? sblock.get(i, j) : 0;
						kplus.execute2( kbuff, genexecDense(valij, b, scalars, n, m, i, j)); 
					}
					c[i] = kbuff._sum;
					lnnz += (c[i]!=0) ? 1 : 0;
				}
			}
		}
		
		return lnnz;
	}

	protected abstract double genexecDense( double a, double[][] b, double[] scalars, int n, int m, int rowIndex, int colIndex);
	
	private class ParAggTask implements Callable<Double> 
	{
		private final MatrixBlock _a;
		private final double[][] _b;
		private final double[] _scalars;
		private final int _clen;
		private final int _rlen;
		private final int _rl;
		private final int _ru;

		protected ParAggTask( MatrixBlock a, double[][] b, double[] scalars, int clen, int rlen, int rl, int ru ) {
			_a = a;
			_b = b;
			_scalars = scalars;
			_clen = clen;
			_rlen = rlen;
			_rl = rl;
			_ru = ru;
		}
		
		@Override
		public Double call() throws DMLRuntimeException {
			return ( !_a.isInSparseFormat()) ?
				executeDenseAndAgg(_a.getDenseBlock(), _b, _scalars, _clen, _rlen, _rl, _ru) :
				executeSparseAndAgg(_a.getSparseBlock(), _b, _scalars, _clen, _rlen, _rl, _ru);
		}
	}

	private class ParExecTask implements Callable<Long> 
	{
		private final MatrixBlock _a;
		private final double[][] _b;
		private final double[] _scalars;
		private final double[] _c;
		private final int _clen;
		private final int _rlen;
		private final int _rl;
		private final int _ru;

		protected ParExecTask( MatrixBlock a, double[][] b, double[] scalars, double[] c, int clen, int rlen, int rl, int ru ) {
			_a = a;
			_b = b;
			_scalars = scalars;
			_c = c;
			_clen = clen;
			_rlen = rlen;
			_rl = rl;
			_ru = ru;
		}
		
		@Override
		public Long call() throws DMLRuntimeException {
			return (!_a.isInSparseFormat()) ?
					executeDense(_a.getDenseBlock(), _b, _scalars, _c, _clen, _rlen, _rl, _ru) :
					executeSparse(_a.getSparseBlock(), _b, _scalars,  _c, _clen, _rlen, _rl, _ru);
		}
	}
}
