/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* $Id$ */

package org.apache.fop.layoutmgr;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.fop.area.Area;
import org.apache.fop.area.BlockParent;
import org.apache.fop.fo.pagination.Flow;

/**
 * LayoutManager for an fo:flow object.
 * Its parent LM is the PageSequenceLayoutManager.
 * This LM is responsible for getting columns of the appropriate size
 * and filling them with block-level areas generated by its children.
 * @todo Reintroduce emergency counter (generate error to avoid endless loop)
 */
public class FlowLayoutManager extends BlockStackingLayoutManager
                               implements BlockLevelLayoutManager {

    /**
     * logging instance
     */
    private static Log log = LogFactory.getLog(FlowLayoutManager.class);

    /** Array of areas currently being filled stored by area class */
    private BlockParent[] currentAreas = new BlockParent[Area.CLASS_MAX];

    /**
     * This is the top level layout manager.
     * It is created by the PageSequence FO.
     * @param pslm parent PageSequenceLayoutManager object
     * @param node Flow object
     */
    public FlowLayoutManager(PageSequenceLayoutManager pslm, Flow node) {
        super(node);
        setParent(pslm);
    }

    /** {@inheritDoc} */
    public List getNextKnuthElements(LayoutContext context, int alignment) {

        List elements = new LinkedList();

        LayoutManager currentChildLM;
        while ((currentChildLM = getChildLM()) != null) {
            if (handleSpanChange(currentChildLM, elements, context)) {
                SpaceResolver.resolveElementList(elements);
                return elements;
            }

            LayoutContext childLC = new LayoutContext(0);
            List childrenElements = getNextChildElements(currentChildLM, context, childLC,
                    alignment);
            if (elements.isEmpty()) {
                context.updateKeepWithPreviousPending(childLC.getKeepWithPreviousPending());
            }
            if (!elements.isEmpty()
                    && !ElementListUtils.startsWithForcedBreak(childrenElements)) {
                addInBetweenBreak(elements, context, childLC);
            }
            context.updateKeepWithNextPending(childLC.getKeepWithNextPending());

            elements.addAll(childrenElements);

            if (ElementListUtils.endsWithForcedBreak(elements)) {
                // a descendant of this flow has break-before or break-after
                if (currentChildLM.isFinished() && !hasNextChildLM()) {
                    setFinished(true);
                }
                SpaceResolver.resolveElementList(elements);
                return elements;
            }
        }

        SpaceResolver.resolveElementList(elements);
        setFinished(true);

        assert !elements.isEmpty();
        return elements;
    }

    private boolean handleSpanChange(LayoutManager childLM, List elements, LayoutContext context) {
        int span = EN_NONE;
        int disableColumnBalancing = EN_FALSE;
        if (childLM instanceof BlockLayoutManager) {
            span = ((BlockLayoutManager)childLM).getBlockFO().getSpan();
            disableColumnBalancing = ((BlockLayoutManager) childLM).getBlockFO()
                    .getDisableColumnBalancing();
        } else if (childLM instanceof BlockContainerLayoutManager) {
            span = ((BlockContainerLayoutManager)childLM).getBlockContainerFO().getSpan();
            disableColumnBalancing = ((BlockContainerLayoutManager) childLM).getBlockContainerFO()
                    .getDisableColumnBalancing();
        }

        int currentSpan = context.getCurrentSpan();
        if (currentSpan != span) {
            if (span == EN_ALL) {
                context.setDisableColumnBalancing(disableColumnBalancing);
            }
            log.debug("span change from " + currentSpan + " to " + span);
            context.signalSpanChange(span);
            return true;
        } else {
            return false;
        }
    }

    private List getNextChildElements(LayoutManager childLM, LayoutContext context,
            LayoutContext childLC, int alignment) {
        childLC.setStackLimitBP(context.getStackLimitBP());
        childLC.setRefIPD(context.getRefIPD());
        childLC.setWritingMode(getCurrentPage().getSimplePageMaster().getWritingMode());

        // get elements from curLM
        List childrenElements = childLM.getNextKnuthElements(childLC, alignment);
        assert !childrenElements.isEmpty();

        // "wrap" the Position inside each element
        List tempList = childrenElements;
        childrenElements = new LinkedList();
        wrapPositionElements(tempList, childrenElements);
        return childrenElements;
    }

    /**
     * {@inheritDoc}
     */
    public int negotiateBPDAdjustment(int adj, KnuthElement lastElement) {
        log.debug(" FLM.negotiateBPDAdjustment> " + adj);

        if (lastElement.getPosition() instanceof NonLeafPosition) {
            // this element was not created by this FlowLM
            NonLeafPosition savedPos = (NonLeafPosition)lastElement.getPosition();
            lastElement.setPosition(savedPos.getPosition());
            int returnValue = ((BlockLevelLayoutManager)lastElement.getLayoutManager())
                    .negotiateBPDAdjustment(adj, lastElement);
            lastElement.setPosition(savedPos);
            log.debug(" FLM.negotiateBPDAdjustment> result " + returnValue);
            return returnValue;
        } else {
            return 0;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void discardSpace(KnuthGlue spaceGlue) {
        log.debug(" FLM.discardSpace> ");

        if (spaceGlue.getPosition() instanceof NonLeafPosition) {
            // this element was not created by this FlowLM
            NonLeafPosition savedPos = (NonLeafPosition)spaceGlue.getPosition();
            spaceGlue.setPosition(savedPos.getPosition());
            ((BlockLevelLayoutManager) spaceGlue.getLayoutManager()).discardSpace(spaceGlue);
            spaceGlue.setPosition(savedPos);
        }
    }

    /** {@inheritDoc} */
    public Keep getKeepTogether() {
        return Keep.KEEP_AUTO;
    }

    /** {@inheritDoc} */
    public Keep getKeepWithNext() {
        return Keep.KEEP_AUTO;
    }

    /** {@inheritDoc} */
    public Keep getKeepWithPrevious() {
        return Keep.KEEP_AUTO;
    }

    /** {@inheritDoc} */
    public List getChangedKnuthElements(List oldList, /*int flaggedPenalty,*/ int alignment) {
        ListIterator oldListIterator = oldList.listIterator();
        KnuthElement returnedElement;
        List returnedList = new LinkedList();
        List returnList = new LinkedList();
        KnuthElement prevElement = null;
        KnuthElement currElement = null;
        int fromIndex = 0;

        // "unwrap" the Positions stored in the elements
        KnuthElement oldElement;
        while (oldListIterator.hasNext()) {
            oldElement = (KnuthElement)oldListIterator.next();
            if (oldElement.getPosition() instanceof NonLeafPosition) {
                // oldElement was created by a descendant of this FlowLM
                oldElement.setPosition((oldElement.getPosition()).getPosition());
            } else {
                // thisElement was created by this FlowLM, remove it
                oldListIterator.remove();
            }
        }
        // reset the iterator
        oldListIterator = oldList.listIterator();


        while (oldListIterator.hasNext()) {
            currElement = (KnuthElement) oldListIterator.next();
            if (prevElement != null
                && prevElement.getLayoutManager() != currElement.getLayoutManager()) {
                // prevElement is the last element generated by the same LM
                BlockLevelLayoutManager prevLM = (BlockLevelLayoutManager)
                                                 prevElement.getLayoutManager();
                BlockLevelLayoutManager currLM = (BlockLevelLayoutManager)
                                                 currElement.getLayoutManager();
                returnedList.addAll(prevLM.getChangedKnuthElements(
                        oldList.subList(fromIndex, oldListIterator.previousIndex()), alignment));
                fromIndex = oldListIterator.previousIndex();

                // there is another block after this one
                if (prevLM.mustKeepWithNext()
                    || currLM.mustKeepWithPrevious()) {
                    // add an infinite penalty to forbid a break between blocks
                    returnedList.add(new KnuthPenalty(0, KnuthElement.INFINITE, false,
                            new Position(this), false));
                } else if (!((KnuthElement) returnedList.get(returnedList
                        .size() - 1)).isGlue()) {
                    // add a null penalty to allow a break between blocks
                    returnedList.add(new KnuthPenalty(0, 0, false, new Position(this), false));
                }
            }
            prevElement = currElement;
        }
        if (currElement != null) {
            BlockLevelLayoutManager currLM = (BlockLevelLayoutManager)
                                             currElement.getLayoutManager();
            returnedList.addAll(currLM.getChangedKnuthElements(
                    oldList.subList(fromIndex, oldList.size()), alignment));
        }

        // "wrap" the Position stored in each element of returnedList
        // and add elements to returnList
        ListIterator listIter = returnedList.listIterator();
        while (listIter.hasNext()) {
            returnedElement = (KnuthElement)listIter.next();
            if (returnedElement.getLayoutManager() != this) {
                returnedElement.setPosition(
                        new NonLeafPosition(this, returnedElement.getPosition()));
            }
            returnList.add(returnedElement);
        }

        return returnList;
    }

    /**
     * {@inheritDoc}
     */
    public void addAreas(PositionIterator parentIter, LayoutContext layoutContext) {
        AreaAdditionUtil.addAreas(this, parentIter, layoutContext);
        flush();
    }

    /**
     * Add child area to a the correct container, depending on its
     * area class. A Flow can fill at most one area container of any class
     * at any one time. The actual work is done by BlockStackingLM.
     *
     * @param childArea the area to add
     */
    public void addChildArea(Area childArea) {
        getParentArea(childArea);
        addChildToArea(childArea,
                          this.currentAreas[childArea.getAreaClass()]);
    }

    /**
     * {@inheritDoc}
     */
    public Area getParentArea(Area childArea) {
        BlockParent parentArea = null;
        int aclass = childArea.getAreaClass();

        if (aclass == Area.CLASS_NORMAL) {
            parentArea = getCurrentPV().getCurrentFlow();
        } else if (aclass == Area.CLASS_BEFORE_FLOAT) {
            parentArea = getCurrentPV().getBodyRegion().getBeforeFloat();
        } else if (aclass == Area.CLASS_FOOTNOTE) {
            parentArea = getCurrentPV().getBodyRegion().getFootnote();
        } else {
            throw new IllegalStateException("(internal error) Invalid "
                    + "area class (" + aclass + ") requested.");
        }

        this.currentAreas[aclass] = parentArea;
        setCurrentArea(parentArea);
        return parentArea;
    }

    /**
     * Returns the IPD of the content area
     * @return the IPD of the content area
     */
    public int getContentAreaIPD() {
        return getCurrentPV().getCurrentSpan().getColumnWidth();
    }

    /**
     * Returns the BPD of the content area
     * @return the BPD of the content area
     */
    public int getContentAreaBPD() {
        return getCurrentPV().getBodyRegion().getBPD();
    }

}

