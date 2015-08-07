package org.numenta.nupic.research;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.numenta.nupic.Connections;
import org.numenta.nupic.model.Cell;
import org.numenta.nupic.model.Column;
import org.numenta.nupic.model.DistalDendrite;
import org.numenta.nupic.model.Synapse;
import org.numenta.nupic.util.SparseObjectMatrix;

/**
 * Temporal Memory implementation in Java
 * 
 * @author Chetan Surpur
 * @author David Ray
 */
public class NewTemporalMemory {
    /**
     * Constructs a new {@code TemporalMemory}
     */
    public NewTemporalMemory() {}
    
    /**
     * Uses the specified {@link Connections} object to Build the structural 
     * anatomy needed by this {@code TemporalMemory} to implement its algorithms.
     * 
     * The connections object holds the {@link Column} and {@link Cell} infrastructure,
     * and is used by both the {@link SpatialPooler} and {@link TemporalMemory}. Either of
     * these can be used separately, and therefore this Connections object may have its
     * Columns and Cells initialized by either the init method of the SpatialPooler or the
     * init method of the TemporalMemory. We check for this so that complete initialization
     * of both Columns and Cells occurs, without either being redundant (initialized more than
     * once). However, {@link Cell}s only get created when initializing a TemporalMemory, because
     * they are not used by the SpatialPooler.
     * 
     * @param   c       {@link Connections} object
     */
    public void init(Connections c) {
        SparseObjectMatrix<Column> matrix = c.getMemory() == null ?
            new SparseObjectMatrix<Column>(c.getColumnDimensions()) :
                c.getMemory();
        c.setMemory(matrix);
        
        int numColumns = matrix.getMaxIndex() + 1;
        int cellsPerColumn = c.getCellsPerColumn();
        Cell[] cells = new Cell[numColumns * cellsPerColumn];
        
        //Used as flag to determine if Column objects have been created.
        Column colZero = matrix.getObject(0);
        for(int i = 0;i < numColumns;i++) {
            Column column = colZero == null ? 
                new Column(cellsPerColumn, i) : matrix.getObject(i);
            for(int j = 0;j < cellsPerColumn;j++) {
                cells[i * cellsPerColumn + j] = column.getCell(j);
            }
            //If columns have not been previously configured
            if(colZero == null) matrix.set(i, column);
        }
        //Only the TemporalMemory initializes cells so no need to test 
        c.setCells(cells);
    }
    
    /////////////////////////// CORE FUNCTIONS /////////////////////////////
    /**
     * Phase 1: Activate the correctly predictive cells
     * 
     * Pseudocode:
     *
     * - for each previous predictive cell
     *   - if in active column
     *     - mark it as active
     *     - mark it as winner cell
     *     - mark column as predicted
     *   - if not in active column
     *     - mark it as a predicted but inactive cell
     *     
     * @param cnx                   Connectivity of layer
     * @param c                     ComputeCycle interim values container
     * @param prevPredictiveCells   predictive {@link Cell}s predictive cells in t-1
     * @param activeColumns         active columns in t
     */
    public void activateCorrectlyPredictiveCells(Connections cnx,
        ComputeCycle c, Set<Cell> prevPredictiveCells, Set<Cell> prevMatchingCells, Set<Column> activeColumns) {
        
        for(Cell cell : prevPredictiveCells) {
            Column column = cell.getParentColumn();
            if(activeColumns.contains(column)) {
                c.activeCells.add(cell);
                c.winnerCells.add(cell);
                c.successfullyPredictedColumns.add(column);
            }
        }
        
        if(cnx.getPredictedSegmentDecrement() > 0) {
            for(Cell cell : prevMatchingCells) {
                Column column = cell.getParentColumn();
                
                if(!activeColumns.contains(column)) {
                    c.predictedInactiveCells.add(cell);
                }
            }
        }
    }
    
    /**
     * Phase 2: Burst unpredicted columns.
     * 
     * Pseudocode:
     *
     * - for each unpredicted active column
     *   - mark all cells as active
     *   - mark the best matching cell as winner cell
     *     - (learning)
     *       - if it has no matching segment
     *         - (optimization) if there are previous winner cells
     *           - add a segment to it
     *       - mark the segment as learning
     * 
     * @param cycle                         ComputeCycle interim values container
     * @param c                             Connections temporal memory state
     * @param activeColumns                 active columns in t
     * @param predictedColumns              predicted columns in t
     * @param prevActiveCells               active {@link Cell}s in t-1
     * @param prevWinnerCells               winner {@link Cell}s in t-1
     */
    public void burstColumns(ComputeCycle cycle, Connections c, 
        Set<Column> activeColumns, Set<Column> predictedColumns, Set<Cell> prevActiveCells, Set<Cell> prevWinnerCells) {
        
        activeColumns.removeAll(predictedColumns); // Now contains unpredicted active columns
        for(Column column : activeColumns) {
            List<Cell> cells = column.getCells();
            cycle.activeCells.addAll(cells);
            
            CellSearch cellSearch = getBestMatchingCell(c, cells, prevActiveCells);
            
            cycle.winnerCells.add(cellSearch.bestCell);
            
            DistalDendrite bestSegment = cellSearch.bestSegment;
            if(bestSegment == null && prevWinnerCells.size() > 0) {
                bestSegment = cellSearch.bestCell.createSegment(c);
            }
            
            cycle.learningSegments.add(bestSegment);
        }
    }
    
    /**
     * Gets the cell with the best matching segment
     * (see `TM.bestMatchingSegment`) that has the largest number of active
     * synapses of all best matching segments.
     *
     * If none were found, pick the least used cell (see `TM.leastUsedCell`).
     *  
     * @param c                 Connections temporal memory state
     * @param columnCells             
     * @param activeCells
     * @return a CellSearch (bestCell, BestSegment)
     */
    public CellSearch getBestMatchingCell(Connections c, List<Cell> columnCells, Set<Cell> activeCells) {
        int maxSynapses = 0;
        Cell bestCell = null;
        DistalDendrite bestSegment = null;
        
        for(Cell cell : columnCells) {
            SegmentSearch bestMatchResult = getBestMatchingSegment(c, cell, activeCells);
            
            if(bestMatchResult.bestSegment != null &&  bestMatchResult.numActiveSynapses > maxSynapses) {
                maxSynapses = bestMatchResult.numActiveSynapses;
                bestCell = cell;
                bestSegment = bestMatchResult.bestSegment;
            }
        }
        
        if(bestCell == null) {
            bestCell = getLeastUsedCell(c, columnCells);
        }
        
        return new CellSearch(bestCell, bestSegment);
    }
    
    /**
     * Gets the segment on a cell with the largest number of activate synapses,
     * including all synapses with non-zero permanences.
     * 
     * @param c
     * @param columnCell
     * @param activeCells
     * @return
     */
    public SegmentSearch getBestMatchingSegment(Connections c, Cell columnCell, Set<Cell> activeCells) {
        int maxSynapses = c.getMinThreshold();
        DistalDendrite bestSegment = null;
        int bestNumActiveSynapses = 0;
        int numActiveSynapses = 0;
        
        for(DistalDendrite segment : c.getSegments(columnCell)) {
            numActiveSynapses = 0;
            for(Synapse synapse : c.getSynapses(segment)) {
                if(activeCells.contains(synapse.getSourceCell()) && synapse.getPermanence() > 0) {
                    ++numActiveSynapses;
                }
            }
            
            if(numActiveSynapses >= maxSynapses) {
                maxSynapses = numActiveSynapses;
                bestSegment = segment;
                bestNumActiveSynapses = numActiveSynapses;
            }
        }
        
        return new SegmentSearch(bestSegment, bestNumActiveSynapses);
    }
    
    /**
     * Gets the cell with the smallest number of segments.
     * Break ties randomly.
     * 
     * @param c
     * @param columnCells
     * @return
     */
    public Cell getLeastUsedCell(Connections c, List<Cell> columnCells) {
        Set<Cell> leastUsedCells = new HashSet<>();
        int minNumSegments = Integer.MAX_VALUE;
        
        for(Cell cell : columnCells) {
            int numSegments = c.getSegments(cell).size();
            
            if(numSegments < minNumSegments) {
                minNumSegments = numSegments;
                leastUsedCells.clear();
            }
            
            if(numSegments == minNumSegments) {
                leastUsedCells.add(cell);
            }
        }
        
        int randomIdx = c.getRandom().nextInt(leastUsedCells.size());
        Cell[] luc;
        Arrays.sort(luc = leastUsedCells.toArray(new Cell[leastUsedCells.size()]));
        return luc[randomIdx];
    }
    
    /**
     * Used locally to return results of column Burst
     */
    class BurstResult {
        Set<Cell> activeCells;
        Set<Cell> winnerCells;
        Set<DistalDendrite> learningSegments;
        
        public BurstResult(Set<Cell> activeCells, Set<Cell> winnerCells, Set<DistalDendrite> learningSegments) {
            super();
            this.activeCells = activeCells;
            this.winnerCells = winnerCells;
            this.learningSegments = learningSegments;
        }
    }
    
    /**
     * Used locally to return best cell/segment pair
     */
    class CellSearch {
        Cell bestCell;
        DistalDendrite bestSegment;
        public CellSearch(Cell bestCell, DistalDendrite bestSegment) {
            this.bestCell = bestCell;
            this.bestSegment = bestSegment;
        }
    }
    
    /**
     * Used locally to return best segment matching results
     */
    class SegmentSearch {
        DistalDendrite bestSegment;
        int numActiveSynapses;
        
        public SegmentSearch(DistalDendrite bestSegment, int numActiveSynapses) {
            this.bestSegment = bestSegment;
            this.numActiveSynapses = numActiveSynapses;
        }
    }
}
