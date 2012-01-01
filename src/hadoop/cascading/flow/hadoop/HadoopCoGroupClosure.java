/*
 * Copyright (c) 2007-2012 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
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

package cascading.flow.hadoop;

import java.util.Iterator;

import cascading.flow.FlowProcess;
import cascading.tuple.Fields;
import cascading.tuple.IndexTuple;
import cascading.tuple.Spillable;
import cascading.tuple.SpillableTupleList;
import cascading.tuple.Tuple;
import cascading.tuple.hadoop.HadoopSpillableTupleList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class CoGroupClosure is used internally to represent co-grouping results of multiple tuple streams.
 * <p/>
 * <p/>
 * "org.apache.hadoop.io.compress.LzoCodec,org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.DefaultCodec"
 */
public class HadoopCoGroupClosure extends HadoopGroupByClosure
  {
  /** Field LOG */
  private static final Logger LOG = LoggerFactory.getLogger( HadoopCoGroupClosure.class );

  /** Field groups */
  SpillableTupleList[] groups;
  private final int numSelfJoins;

  public HadoopCoGroupClosure( FlowProcess flowProcess, int numSelfJoins, Fields[] groupingFields, Fields[] valueFields )
    {
    super( flowProcess, groupingFields, valueFields );
    this.numSelfJoins = numSelfJoins;

    initLists( flowProcess );
    }

  @Override
  public int size()
    {
    return groups.length;
    }

  @Override
  public Iterator<Tuple> getIterator( int pos )
    {
    if( pos < 0 || pos >= groups.length )
      throw new IllegalArgumentException( "invalid group position: " + pos );

    return makeIterator( pos, groups[ pos ].iterator() );
    }

  @Override
  public boolean isEmpty( int pos )
    {
    return groups[ pos ].isEmpty();
    }

  @Override
  public void reset( Tuple grouping, Iterator values )
    {
    super.reset( grouping, values );

    build();
    }

  private void build()
    {
    clearGroups();

    while( values.hasNext() )
      {
      IndexTuple current = (IndexTuple) values.next();
      int pos = current.getIndex();

      // if this is the first (lhs) co-group, just use values iterator
      if( numSelfJoins == 0 && pos == 0 )
        {
        groups[ pos ].setIterator( current, values );
        break;
        }

      groups[ pos ].add( (Tuple) current.getTuple() ); // get the value tuple for this cogroup
      }
    }

  private void clearGroups()
    {
    for( SpillableTupleList group : groups )
      group.clear();
    }

  private void initLists( FlowProcess flowProcess )
    {
    int numPipes = joinFields.length;
    groups = new SpillableTupleList[ Math.max( numPipes, numSelfJoins + 1 ) ];

    long threshold = getLong( HadoopProperties.COGROUP_SPILL_THRESHOLD, HadoopProperties.defaultThreshold );

    for( int i = 0; i < numPipes; i++ ) // use numPipes not numSelfJoins, see below
      {
      groups[ i ] = new HadoopSpillableTupleList( threshold, (HadoopFlowProcess) flowProcess );
      groups[ i ].setListener( createListener( joinFields[ i ] ) );
      }

    for( int i = 1; i < numSelfJoins + 1; i++ )
      groups[ i ] = groups[ 0 ];
    }

  private long getLong( String key, long defaultValue )
    {
    String value = (String) flowProcess.getProperty( key );

    if( value == null || value.length() == 0 )
      return defaultValue;

    return Long.parseLong( value );
    }

  private Spillable.Listener createListener( final Fields joinField )
    {
    return new Spillable.Listener()
    {
    @Override
    public void notify( Spillable spillable )
      {
      int numFiles = ( (SpillableTupleList) spillable ).getNumFiles();

      if( ( numFiles - 1 ) % 10 == 0 )
        {
        LOG.info( "spilled group: {}, on grouping: {}, num times: {}",
          new Object[]{joinField.printVerbose(), getGrouping().print(), numFiles} );

        Runtime runtime = Runtime.getRuntime();
        long freeMem = runtime.freeMemory() / 1024 / 1024;
        long maxMem = runtime.maxMemory() / 1024 / 1024;
        long totalMem = runtime.totalMemory() / 1024 / 1024;

        LOG.info( "mem on spill (mb), free: " + freeMem + ", total: " + totalMem + ", max: " + maxMem );
        }
      }
    };
    }
  }