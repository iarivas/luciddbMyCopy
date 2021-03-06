/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2005 SQLstream, Inc.
// Copyright (C) 2005 Dynamo BI Corporation
// Portions Copyright (C) 1999 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

#ifndef Fennel_Segment_Included
#define Fennel_Segment_Included

#include "fennel/cache/MappedPageListener.h"
#include "fennel/common/CompoundId.h"
#include "fennel/common/ClosableObject.h"

#include <boost/enable_shared_from_this.hpp>
#include <boost/utility.hpp>
#include <hash_map>

FENNEL_BEGIN_NAMESPACE

/**
 * Segment is a virtualization layer for allocating and accessing pages of
 * device storage via the cache.  See <a
 * href="structSegmentDesign.html#Overview">the design docs</a> for more
 * detail.
 */
class FENNEL_SEGMENT_EXPORT Segment
    : public MappedPageListener,
        public boost::noncopyable,
        public ClosableObject,
        public boost::enable_shared_from_this<Segment>
{
    /**
     * Number of usable bytes on each page before footer.
     */
    uint cbUsablePerPage;

protected:

    typedef std::hash_map<PageId, PageId> PageMap;
    typedef PageMap::const_iterator PageMapConstIter;

    /**
     * Cache managing pages of this segment.
     */
    SharedCache pCache;

    /**
     * The tracing segment associated with this segment, if tracing is turned
     * on.  A weak_ptr is used due to the circular shared pointers between
     * this segment and its tracing segment.
     */
    WeakSegment pTracingSegment;

    explicit Segment(SharedCache);
    void setUsablePageSize(uint);
    PConstBuffer getReadableFooter(CachePage &page);
    PBuffer getWritableFooter(CachePage &page);

    // helper methods for LINEAR_ALLOCATION segments
    /**
     * An implementation of getPageSuccessor suitable for
     * LINEAR_ALLOCATION.
     */
    PageId getLinearPageSuccessor(PageId pageId);

    /**
     * An implementation of setPageSuccessor suitable for
     * LINEAR_ALLOCATION.
     */
    void setLinearPageSuccessor(PageId pageId, PageId successorId);

    /**
     * An implementation of isPageIdAllocated suitable for
     * LINEAR_ALLOCATION when deallocation holes are disallowed.
     */
    bool isLinearPageIdAllocated(PageId pageId);

    // implement ClosableObject
    virtual void closeImpl();

public:

    /**
     * Enumeration of the possible orderings of PageIds returned from
     * allocatePageId.  The enumeration is from weakest to strongest
     * ordering, and should not be changed.
     */
    enum AllocationOrder {
        /**
         * Random order.
         */
        RANDOM_ALLOCATION,

        /**
         * Later calls always return greater PageIds, but not necessarily
         * consecutively.
         */
        ASCENDING_ALLOCATION,

        /**
         * PageIds are returned in consecutive ascending order of BlockNum; the
         * DeviceId is always the same.
         */
        CONSECUTIVE_ALLOCATION,

        /**
         * PageIds are returned in consecutive ascending order starting with
         * 0; all bytes of the PageId are used (no division into
         * DeviceId/BlockNum), yielding maximum range.
         */
        LINEAR_ALLOCATION
    };

    /**
     * Destructor.  As a side-effect of closing a segment, a call to
     * checkpoint() with CHECKPOINT_FLUSH_AND_UNMAP is made so that all pages
     * are guaranteed to be unmapped before destruction.
     */
    virtual ~Segment();

    /**
     * @return the Cache for this Segment
     */
    inline SharedCache getCache() const;

    /**
     * @return the full size of pages stored in this segment; this is the same
     * as the size of underlying cache pages
     */
    uint getFullPageSize() const;

    /**
     * @return the full size of pages stored in this segment minus the size for
     * any footer information stored at the end of each page
     */
    inline uint getUsablePageSize() const;

    /**
     * Performs additional initialization required on the segment after it
     * has been properly formatted.
     */
    virtual void initForUse();

    /**
     * @return number of pages allocated from this segment
     */
    virtual BlockNum getAllocatedSizeInPages() = 0;

    /**
     * Returns the max number of pages occupied by this segment instance.
     * In other words, pages that are allocated but subsequently deallocated,
     * are included in this count.  Also, the count includes all pages used
     * by the segment, including metadata pages.
     *
     * @return the max number of pages occupied by a segment
     */
    virtual BlockNum getNumPagesOccupiedHighWater() = 0;

    /**
     * @return the number of incremental pages added to this instance of the
     * segment
     */
    virtual BlockNum getNumPagesExtended() = 0;

    /**
     * @return tracing segment associated with this segment if tracing is turned
     * on; otherwise, returns the segment itself
     */
    SharedSegment getTracingSegment();

    /**
     * Sets the tracing segment associated with this segment
     *
     * @param pTracingSegmentInit the tracing segment
     */
    void setTracingSegment(WeakSegment pTracingSegmentInit);

    /**
     * Checkpoints this segment.
     *
     * @param checkpointType type of checkpoint to execute
     */
    void checkpoint(
        CheckpointType checkpointType = CHECKPOINT_FLUSH_ALL);

    /**
     * Helper for DelegatingSegment.
     *
     * @param delegatingSegment the Segment on which checkpoint was originally
     * called
     *
     * @param checkpointType type of checkpoint requested
     */
    virtual void delegatedCheckpoint(
        Segment &delegatingSegment,
        CheckpointType checkpointType);

    /**
     * Determines the successor of a given PageId.  This is an optional
     * interface only supported by segments with some concept of page ordering.
     *
     * @param pageId PageId for which the successor is to be found
     *
     * @return successor PageId
     */
    virtual PageId getPageSuccessor(PageId pageId) = 0;

    /**
     * Sets the successor of a given PageId.  This is an optional interface only
     * supported by segments with some concept of modifiable ordering.
     *
     * @param pageId PageId for which the successor is to be set
     *
     * @param successorId PageId of successor
     */
    virtual void setPageSuccessor(PageId pageId, PageId successorId) = 0;

    /**
     * @return the AllocationOrder for this segment
     */
    virtual AllocationOrder getAllocationOrder() const = 0;

    /**
     * Maps from a PageId in this segment to a BlockId.
     */
    virtual BlockId translatePageId(PageId) = 0;

    /**
     * Maps from a BlockId to a PageId in this segment.
     */
    virtual PageId translateBlockId(BlockId) = 0;

    /**
     * Allocates a page without locking it into memory.
     *
     * @param ownerId the PageOwnerId of the object which will own this page,
     * or ANON_PAGE_OWNER_ID for pages unassociated with an owner
     *
     * @return the PageId of the allocated page, or NULL_PAGE_ID if none
     * could be allocated
     */
    virtual PageId allocatePageId(PageOwnerId ownerId = ANON_PAGE_OWNER_ID) = 0;

    /**
     * Allocates pages as needed to make getAllocatedSizeInPages() meet
     * a lower bound.  The PageId's of the allocated pages are not returned,
     * so this is mostly only meaningful for linear segments.
     *
     * @param nPages lower bound for getAllocatedSizeInPages()
     *
     * @return true if enough pages could be allocated; false if not
     */
    virtual bool ensureAllocatedSize(BlockNum nPages);

    /**
     * Deallocates a range of pages allocated from this segment.  Some segment
     * implementations may impose restrictions on the range
     * (e.g. individual pages only, entire segment truncation only,
     * start-ranges, or end-ranges).  The interpretation of the range
     * may also vary by segment (e.g. for a LINEAR_ALLOCATION segment,
     * it's a simple linear PageId range, while for a RANDOM_ALLOCATION
     * segment, successors could be used).
     *
     *<p>
     *
     * Depending on the circumstances, it may be the responsibility of the
     * segment to discard the corresponding blocks from the cache.  The details
     * vary by segment implementation.
     *
     * @param startPageId inclusive start of PageId range to deallocate, or
     * default NULL_PAGE_ID for beginning of segment
     *
     * @param endPageId inclusive end of PageId range to deallocate,
     * or default NULL_PAGE_ID for end of segment
     */
    virtual void deallocatePageRange(
        PageId startPageId,
        PageId endPageId) = 0;

    /**
     * Tests whether a PageId is allocated.
     *
     * @param pageId the PageId of interest
     *
     * @return true iff the PageId is currently allocated in this segment
     */
    virtual bool isPageIdAllocated(PageId pageId) = 0;

    /**
     * Determines whether a page can be updated in-place, and if so, prepares
     * the page for update.
     *
     * @param pageId pageId of the page being modified
     *
     * @param needsTranslation true if the pageId needs to be mapped to the
     * appropriate update page; defaults to false
     *
     * @return NULL_PAGE_ID if the page can be updated in place; otherwise, the
     * pageId of the page that should be used when updates are made to the page
     */
    virtual PageId updatePage(PageId pageId, bool needsTranslation = false);

    /**
     * Returns the mapped page listener corresponding to a page.
     *
     * @param blockId blockId of the page whose page listener we are returning
     *
     * @return segment corresponding to mapped page listener
     */
    virtual MappedPageListener *getMappedPageListener(BlockId blockId);

    /**
     * @return true if the segment supports versioning
     */
    virtual bool isWriteVersioned();

    /**
     * Constructs a linear PageId based on a linear page number.
     */
    static PageId getLinearPageId(BlockNum iPage);

    /**
     * Obtains the linear page number from a linear PageId.
     */
    static BlockNum getLinearBlockNum(PageId pageId);

    // implement MappedPageListener
    virtual MappedPageListener *getTracingListener();
};

inline PageId Segment::getLinearPageId(BlockNum iPage)
{
    return PageId(iPage);
}

inline BlockNum Segment::getLinearBlockNum(PageId pageId)
{
    return opaqueToInt(pageId);
}

inline SharedCache Segment::getCache() const
{
    return pCache;
}

inline uint Segment::getUsablePageSize() const
{
    return cbUsablePerPage;
}

FENNEL_END_NAMESPACE

#endif

// End Segment.h
