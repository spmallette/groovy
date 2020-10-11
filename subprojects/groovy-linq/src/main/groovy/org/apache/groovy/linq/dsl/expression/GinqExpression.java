/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.groovy.linq.dsl.expression;

import org.apache.groovy.linq.dsl.GinqVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represent the root expression of GINQ
 *
 * @since 4.0.0
 */
public class GinqExpression extends AbstractGinqExpression {
    private FromExpression fromExpression;
    private final List<JoinExpression> joinExpressionList = new ArrayList<>();
    private SelectExpression selectExpression;

    @Override
    public <R> R accept(GinqVisitor<R> visitor) {
        return visitor.visitGinqExpression(this);
    }

    public FromExpression getFromExpression() {
        return fromExpression;
    }

    public void setFromExpression(FromExpression fromExpression) {
        this.fromExpression = fromExpression;
    }

    public List<JoinExpression> getJoinExpressionList() {
        return joinExpressionList;
    }

    public void addJoinExpression(JoinExpression joinExpression) {
        joinExpressionList.add(joinExpression);
    }

    public SelectExpression getSelectExpression() {
        return selectExpression;
    }

    public void setSelectExpression(SelectExpression selectExpression) {
        this.selectExpression = selectExpression;
    }

    @Override
    public String toString() {
        return "GinqExpression{" +
                "fromExpression=" + fromExpression +
                ", selectExpression=" + selectExpression +
                '}';
    }
}
