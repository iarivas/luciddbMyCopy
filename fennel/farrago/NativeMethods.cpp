/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2003 SQLstream, Inc.
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

#include "fennel/common/CommonPreamble.h"
#include "fennel/farrago/NativeMethods.h"
#include "fennel/farrago/CmdInterpreter.h"
#include "fennel/farrago/JavaTransformExecStream.h"
#include "fennel/farrago/JniUtil.h"
#include "fennel/tuple/TupleAccessor.h"
#include "fennel/tuple/AttributeAccessor.h"
#include "fennel/farrago/Fem.h"
#include "fennel/tuple/StandardTypeDescriptor.h"
#include "fennel/common/ByteInputStream.h"
#include "fennel/segment/DynamicDelegatingSegment.h"
#include "fennel/segment/SegmentFactory.h"
#include "fennel/db/Database.h"
#include "fennel/exec/ExecStreamGraph.h"
#include "fennel/exec/ExecStreamScheduler.h"
#include "fennel/exec/ExecStreamBufAccessor.h"
#include "fennel/exec/ExecStreamGovernor.h"

#include <sstream>
#include <iostream>
#include <string>

#ifdef __MSVC__
#include <windows.h>
#else
#include <dlfcn.h>
#endif

// TODO:  figure out how to get compiler to cross-check method declarations in
// NativeMethods.h!

FENNEL_BEGIN_CPPFILE("$Id$");

#define JAVAOBJECTHANDLE_TYPE_STR ("JavaObjectHandle")

#ifdef __MSVC__
extern "C" JNIEXPORT BOOL APIENTRY DllMain(
    HANDLE hModule,
    DWORD  ul_reason_for_call,
    LPVOID lpReserved)
{
    return TRUE;
}
#endif

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm,void *reserved)
{
    JniUtil::initDebug("FENNEL_JNI_DEBUG");
    jint version = JniUtil::init(vm);
    JniEnvAutoRef pEnv;
    try {
        staticInitFem(pEnv, FemVisitor::visitTbl);
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
    }

    // REVIEW jvs 26-Nov-2004:  I had to put this in to squelch strange
    // shutdown problems when extension JNI libraries (such as
    // libfarrago_dt and libfarrago_lu) are loaded.  It pins our .so
    // artificially, which probably isn't a good thing either.
#ifndef __MSVC__
    dlopen("libfarrago.so", RTLD_NOW | RTLD_GLOBAL);
#endif

    return version;
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_sf_farrago_fennel_FennelStorage_executeJavaCmd(
    JNIEnv *pEnvInit, jclass, jobject jCmd, jlong jExecHandle)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        ProxyCmd cmd;
        cmd.init(pEnv, jCmd);
        CmdInterpreter cmdInterpreter;
        if (jExecHandle == 0) {
            cmdInterpreter.pExecHandle = NULL;
        } else {
            CmdInterpreter::ExecutionHandle &execHandle =
                CmdInterpreter::getExecutionHandleFromLong(jExecHandle);
            cmdInterpreter.pExecHandle = &execHandle;
        }
        return cmdInterpreter.executeCommand(cmd);
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamFetch(
    JNIEnv *pEnvInit, jclass, jlong hStream, jbyteArray byteArray)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        ExecStream &stream =
            CmdInterpreter::getExecStreamFromLong(hStream);
        ExecStreamScheduler *scheduler = stream.getGraph().getScheduler();
        assert(scheduler);
        ExecStreamBufAccessor &bufAccessor = scheduler->readStream(stream);
        if (bufAccessor.getState() == EXECBUF_EOS) {
            return 0;
        }
        assert(bufAccessor.isConsumptionPossible());
        uint cbLimit = uint(pEnv->GetArrayLength(byteArray));
        uint cbActual = bufAccessor.getConsumptionAvailableBounded(cbLimit);
        assert(cbActual);
        PConstBuffer pBuffer = bufAccessor.getConsumptionStart();
        assert(cbLimit >= cbActual);
        pEnv->SetByteArrayRegion(
            byteArray, 0, cbActual, (jbyte *)(pBuffer));
        bufAccessor.consumeData(pBuffer + cbActual);
        return cbActual;
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamTransformFetch(
    JNIEnv *pEnvInit, jclass, jlong hStream, jint inputOrdinal,
    jbyteArray byteArray)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        ExecStream &stream =
            CmdInterpreter::getExecStreamFromLong(hStream);

        uint iInput = static_cast<uint>(inputOrdinal);

        SharedExecStreamBufAccessor bufAccessor =
            stream.getGraph().getStreamInputAccessor(
                stream.getStreamId(), iInput);

        if (bufAccessor->getState() == EXECBUF_EOS) {
            return 0;
        }

        if (!bufAccessor->isConsumptionPossible()) {
            return -1;
        }

        uint cbLimit = uint(pEnv->GetArrayLength(byteArray));
        uint cbActual = bufAccessor->getConsumptionAvailableBounded(cbLimit);
        assert(cbActual);
        PConstBuffer pBuffer = bufAccessor->getConsumptionStart();
        assert(cbLimit >= cbActual);
        pEnv->SetByteArrayRegion(
            byteArray, 0, cbActual, (jbyte *)(pBuffer));
        bufAccessor->consumeData(pBuffer + cbActual);
        return cbActual;
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamGraphGetInputStreams(
    JNIEnv *pEnvInit, jclass,
    jlong hStreamGraph, jstring baseName, jobject inputStreamNameList)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        // TODO lift these to JniUtil:
        jclass classList = pEnv->FindClass("java/util/List");
        jmethodID methListAdd =
            pEnv->GetMethodID(classList, "add", "(Ljava/lang/Object;)Z");
        CmdInterpreter::StreamGraphHandle &streamGraphHandle =
            CmdInterpreter::getStreamGraphHandleFromLong(hStreamGraph);
        SharedExecStreamGraph pgraph = streamGraphHandle.pExecStreamGraph;
        assert(pgraph);

        SharedExecStream base =
            pgraph->findStream(JniUtil::toStdString(pEnv, baseName));
        assert(base);
        uint ct = pgraph->getInputCount(base->getStreamId());
        for (uint i = 0; i < ct; i++) {
            SharedExecStream input =
                pgraph->getStreamInput(base->getStreamId(), i);
            assert(input);
            jstring inputName = pEnv->NewStringUTF(input->getName().c_str());
            pEnv->CallObjectMethod(inputStreamNameList, methListAdd, inputName);
        }
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamRestart(
    JNIEnv *pEnvInit, jclass, jlong hStream)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        ExecStream &stream =
            CmdInterpreter::getExecStreamFromLong(hStream);
        stream.open(true);
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamSetRunnable(
    JNIEnv *pEnvInit, jclass, jlong hStream, jboolean state)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        ExecStream &stream = CmdInterpreter::getExecStreamFromLong(hStream);
        ExecStreamScheduler *scheduler = stream.getGraph().getScheduler();
        assert(scheduler);
        scheduler->setRunnable(stream, state);
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamGraphOpen(
    JNIEnv *pEnvInit, jclass, jlong hStreamGraph, jlong hTxn,
    jobject hJavaStreamMap, jobject hJavaErrorTarget)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        CmdInterpreter::StreamGraphHandle &streamGraphHandle =
            CmdInterpreter::getStreamGraphHandleFromLong(hStreamGraph);
        CmdInterpreter::TxnHandle &txnHandle =
            CmdInterpreter::getTxnHandleFromLong(hTxn);
        // Provide runtime context for stream open(), which a scheduler may
        // defer until after our java caller returns: hence the global ref.
        if (streamGraphHandle.javaRuntimeContext) {
            // TODO jvs 13-May-2010:  Use a shared pointer for this
            // like we do with ErrorTarget, and track its JNI handle.
            pEnv->DeleteGlobalRef(streamGraphHandle.javaRuntimeContext);
            streamGraphHandle.javaRuntimeContext = NULL;
        }
        streamGraphHandle.javaRuntimeContext =
            pEnv->NewGlobalRef(hJavaStreamMap);
        streamGraphHandle.pExecStreamGraph->setTxn(txnHandle.pTxn);

        // When snapshots are enabled, switch the delegating segment so
        // the stream graph accesses the snapshot segment associated with
        // the current txn
        SharedDatabase pDb = txnHandle.pDb;
        if (pDb->areSnapshotsEnabled()) {
            DynamicDelegatingSegment *pSegment =
                SegmentFactory::dynamicCast<DynamicDelegatingSegment *>(
                    streamGraphHandle.pSegment);
            pSegment->setDelegatingSegment(
                WeakSegment(txnHandle.pSnapshotSegment));
            pSegment =
                SegmentFactory::dynamicCast<DynamicDelegatingSegment *>(
                    streamGraphHandle.pReadCommittedSegment);
            pSegment->setDelegatingSegment(
                WeakSegment(txnHandle.pReadCommittedSnapshotSegment));
        }
        streamGraphHandle.pExecStreamGraph->setErrorTarget(
            CmdInterpreter::newErrorTarget(hJavaErrorTarget));
        txnHandle.pResourceGovernor->requestResources(
            *(streamGraphHandle.pExecStreamGraph));
        streamGraphHandle.pExecStreamGraph->open();
        if (streamGraphHandle.pScheduler.unique()) {
            streamGraphHandle.pScheduler->start();
        }
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_tupleStreamGraphClose(
    JNIEnv *pEnvInit, jclass, jlong hStreamGraph, jint action)
{
    JniEnvRef pEnv(pEnvInit);
    try {
        CmdInterpreter::StreamGraphHandle &streamGraphHandle =
            CmdInterpreter::getStreamGraphHandleFromLong(hStreamGraph);
        switch (action) {
        case net_sf_farrago_fennel_FennelStorage_CLOSE_RESULT:
            if (streamGraphHandle.pScheduler.unique()) {
                streamGraphHandle.pScheduler->stop();
            }
            if (streamGraphHandle.pExecStreamGraph) {
                streamGraphHandle.pExecStreamGraph->close();
            }
            if (streamGraphHandle.javaRuntimeContext) {
                pEnv->DeleteGlobalRef(streamGraphHandle.javaRuntimeContext);
                streamGraphHandle.javaRuntimeContext = NULL;
            }
            break;
        case net_sf_farrago_fennel_FennelStorage_CLOSE_ABORT:
            if (streamGraphHandle.pScheduler) {
                if (streamGraphHandle.pExecStreamGraph) {
                    streamGraphHandle.pScheduler->abort(
                        *(streamGraphHandle.pExecStreamGraph));
                }
            }
            break;
        case net_sf_farrago_fennel_FennelStorage_CLOSE_DEALLOCATE:
            if (streamGraphHandle.pScheduler) {
                if (streamGraphHandle.pScheduler.unique()) {
                    streamGraphHandle.pScheduler->stop();
                }
                streamGraphHandle.pScheduler->removeGraph(
                    streamGraphHandle.pExecStreamGraph);
            }
            delete &streamGraphHandle;
            break;
        default:
            permAssert(false);
        }
    } catch (std::exception &ex) {
        pEnv.handleExcn(ex);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_sf_farrago_fennel_FennelStorage_getAccessorXmiForTupleDescriptor(
    JNIEnv *pEnvInit, jclass, jobject jTupleDesc)
{
    JniEnvRef pEnv(pEnvInit);

    // NOTE: Since JniProxies are currently read-only, generate XMI
    // representation instead.  If more of this kind of thing starts to
    // accumulate, making the JniProxies read-write would be a good idea.

    ProxyTupleDescriptor proxyTupleDesc;
    proxyTupleDesc.init(pEnv, jTupleDesc);

    // TODO:  excn handling?

    // TODO:  should take database handle and use its factory instead
    StandardTypeDescriptorFactory typeFactory;
    TupleDescriptor tupleDescriptor;
    CmdInterpreter::readTupleDescriptor(
        tupleDescriptor,
        proxyTupleDesc,
        typeFactory);
    TupleAccessor tupleAccessor;
    tupleAccessor.compute(tupleDescriptor);
    std::ostringstream oss;
    oss << "<XMI xmi.version = '1.2' "
        << "xmlns:FEMFennel = 'org.omg.xmi.namespace.FEMFennel'>" << std::endl;
    oss << "<XMI.content>" << std::endl;
    oss << "<FEMFennel:TupleAccessor minByteLength='";
    oss << tupleAccessor.getMinByteCount();
    oss << "' bitFieldOffset='";
    if (!isMAXU(tupleAccessor.getBitFieldOffset())) {
        oss << tupleAccessor.getBitFieldOffset();
    } else {
        oss << "-1";
    }
    oss << "'>" << std::endl;
    for (uint i = 0; i < tupleDescriptor.size(); ++i) {
        AttributeAccessor const &attrAccessor =
            tupleAccessor.getAttributeAccessor(i);
        oss << "<FEMFennel:TupleAccessor.AttrAccessor>";
        oss << "<FEMFennel:TupleAttrAccessor ";
        oss << "nullBitIndex='";
        if (!isMAXU(attrAccessor.iNullBit)) {
            oss << attrAccessor.iNullBit;
        } else {
            oss << "-1";
        }
        oss << "' ";
        oss << "fixedOffset='";
        if (!isMAXU(attrAccessor.iFixedOffset)) {
            oss << attrAccessor.iFixedOffset;
        } else {
            oss << "-1";
        }
        oss << "' ";
        oss << "endIndirectOffset='";
        if (!isMAXU(attrAccessor.iEndIndirectOffset)) {
            oss << attrAccessor.iEndIndirectOffset;
        } else {
            oss << "-1";
        }
        oss << "' ";
        oss << "bitValueIndex='";
        if (!isMAXU(attrAccessor.iValueBit)) {
            oss << attrAccessor.iValueBit;
        } else {
            oss << "-1";
        }
        oss << "' ";
        oss << "/>" << std::endl;
        oss << "</FEMFennel:TupleAccessor.AttrAccessor>";
    }
    oss << "</FEMFennel:TupleAccessor>" << std::endl;
    oss << "</XMI.content>" << std::endl;
    oss << "</XMI>" << std::endl;
    std::string s = oss.str();
    return pEnv->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_sf_farrago_fennel_FennelStorage_newObjectHandle(
    JNIEnv *pEnvInit, jclass, jobject obj)
{
    // TODO:  excn handling?

    JniEnvRef pEnv(pEnvInit);
    jobject jGlobalRef;
    if (obj) {
        jGlobalRef = pEnv->NewGlobalRef(obj);
        // TODO:  convert to Java excn instead
        assert(jGlobalRef);
    } else {
        jGlobalRef = NULL;
    }
    jobject *pGlobalRef = new jobject;
    JniUtil::incrementHandleCount(JAVAOBJECTHANDLE_TYPE_STR, pGlobalRef);
    *pGlobalRef = jGlobalRef;
    return reinterpret_cast<jlong>(pGlobalRef);
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_deleteObjectHandle(
    JNIEnv *pEnvInit, jclass, jlong handle)
{
    // TODO:  excn handling?

    JniEnvRef pEnv(pEnvInit);
    jobject *pGlobalRef = reinterpret_cast<jobject *>(handle);
    jobject jGlobalRef = *pGlobalRef;
    if (jGlobalRef) {
        pEnv->DeleteGlobalRef(jGlobalRef);
    }
    delete pGlobalRef;
    JniUtil::decrementHandleCount(JAVAOBJECTHANDLE_TYPE_STR, pGlobalRef);
}

// TODO:  share code with new/delete

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_setObjectHandle(
    JNIEnv *pEnvInit, jclass, jlong handle, jobject obj)
{
    // TODO:  excn handling?

    JniEnvRef pEnv(pEnvInit);
    jobject *pGlobalRef = reinterpret_cast<jobject *>(handle);
    jobject jGlobalRef = *pGlobalRef;
    if (jGlobalRef) {
        pEnv->DeleteGlobalRef(jGlobalRef);
    }
    if (obj) {
        jGlobalRef = pEnv->NewGlobalRef(obj);
        // TODO:  convert to Java excn instead
        assert(jGlobalRef);
    } else {
        jGlobalRef = NULL;
    }
    *pGlobalRef = jGlobalRef;
}

extern "C" JNIEXPORT jint JNICALL
Java_net_sf_farrago_fennel_FennelStorage_getHandleCount(
    JNIEnv *pEnvInit, jclass)
{
    return JniUtil::getHandleCount();
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_sf_farrago_fennel_FennelStorage_newExecutionHandle(
    JNIEnv *pEnvInit, jclass)
{
    CmdInterpreter::ExecutionHandle *pExecHandle =
        new CmdInterpreter::ExecutionHandle();
    JniUtil::incrementHandleCount(EXECHANDLE_TRACE_TYPE_STR, pExecHandle);
    return reinterpret_cast<int64_t>(pExecHandle);
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_deleteExecutionHandle(
    JNIEnv *pEnvInit, jclass, jlong handle)
{
    CmdInterpreter::ExecutionHandle &execHandle =
        CmdInterpreter::getExecutionHandleFromLong(handle);
    CmdInterpreter::ExecutionHandle *pExecHandle = &execHandle;
    JniUtil::decrementHandleCount(EXECHANDLE_TRACE_TYPE_STR, pExecHandle);
    deleteAndNullify(pExecHandle);
}

extern "C" JNIEXPORT void JNICALL
Java_net_sf_farrago_fennel_FennelStorage_cancelExecution(
    JNIEnv *pEnvInit, jclass, jlong handle)
{
    CmdInterpreter::ExecutionHandle &execHandle =
        CmdInterpreter::getExecutionHandleFromLong(handle);
    execHandle.aborted = true;
}

FENNEL_END_CPPFILE("$Id$");

// End NativeMethods.cpp
