/*
 * Copyright (c) 2006-2007, AIOTrade Computing Co. and Contributors
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 *  o Redistributions of source code must retain the above copyright notice, 
 *    this list of conditions and the following disclaimer. 
 *    
 *  o Redistributions in binary form must reproduce the above copyright notice, 
 *    this list of conditions and the following disclaimer in the documentation 
 *    and/or other materials provided with the distribution. 
 *    
 *  o Neither the name of AIOTrade Computing Co. nor the names of 
 *    its contributors may be used to endorse or promote products derived 
 *    from this software without specific prior written permission. 
 *    
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR 
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR 
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, 
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; 
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR 
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, 
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.aiotrade.platform.modules.indicator.basic;

import org.aiotrade.lib.math.timeseries.plottable.Plot;
import org.aiotrade.platform.core.analysis.indicator.AbstractContIndicator;
import org.aiotrade.platform.core.analysis.indicator.AbstractIndicator.DefaultOpt;
import org.aiotrade.platform.core.analysis.indicator.IndicatorName;
import org.aiotrade.lib.math.timeseries.computable.Opt;
import org.aiotrade.lib.math.timeseries.DefaultSer.DefaultVar;
import org.aiotrade.lib.math.timeseries.Var;

/**
 *
 * @author Caoyuan Deng
 */
@IndicatorName("WMS")
public class WMSIndicator extends AbstractContIndicator {
    
    Opt period1 = new DefaultOpt("Period Short",  6.0);
    Opt period2 = new DefaultOpt("Period Long",  10.0);
    
    Var<Float> wms1 = new DefaultVar("WMS1", Plot.Line);
    Var<Float> wms2 = new DefaultVar("WMS2", Plot.Line);
    
    {
        _sname = "WMS";
        _lname = "Williams' %R";
        _grids = new Float[] {10f, 90f};
    }
    
    protected void computeCont(int begIdx) {
        for (int i = begIdx; i < _itemSize; i++) {
            wms1.set(i, wms(i, period1));
            wms2.set(i, wms(i, period2));
        }
    }
    
}


