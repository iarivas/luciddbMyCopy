/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2004 SQLstream, Inc.
// Copyright (C) 2009 Dynamo BI Corporation
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

#include "fennel/common/CommonPreamble.h"
#include "fennel/calculator/CalcInit.h"

// to allow calls to InstructionFactory::registerInstructions()
#include "fennel/calculator/CalcAssembler.h"
#include "fennel/calculator/InstructionFactory.h"

FENNEL_BEGIN_NAMESPACE

CalcInit* CalcInit::_instance = NULL;

CalcInit*
CalcInit::instance()
{
    // Warning: Not thread safe
    if (_instance) {
        return _instance;
    }

    _instance = new CalcInit;

    InstructionFactory::registerInstructions();

    ExtStringRegister(InstructionFactory::getExtendedInstructionTable());
    ExtMathRegister(InstructionFactory::getExtendedInstructionTable());
    ExtDateTimeRegister(InstructionFactory::getExtendedInstructionTable());
    ExtRegExpRegister(InstructionFactory::getExtendedInstructionTable());
    ExtCastRegister(InstructionFactory::getExtendedInstructionTable());
    ExtDynamicVariableRegister(
        InstructionFactory::getExtendedInstructionTable());
    ExtWinAggFuncRegister(InstructionFactory::getExtendedInstructionTable());

    // Add new init calls here

    return _instance;
}


FENNEL_END_NAMESPACE

// End CalcInit.cpp
