/*
 *  Copyright (c) 2022 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *
 */

package org.eclipse.dse.issuerservice.store.sql.postgres;

import org.eclipse.dse.issuerservice.store.sql.schema.MembershipAttestationStatements;
import org.eclipse.edc.sql.translation.TranslationMapping;

public class MembershipAttestationMapping extends TranslationMapping {
    public MembershipAttestationMapping(MembershipAttestationStatements statements) {
        add("id", statements.getIdColumn());
        add("holderId", statements.getHolderIdColumn());
        add("membershipType", statements.getMembershipTypeColumn());
        add("membershipStartDate", statements.getMembershipStartDateColumn());
        add("name", statements.getNameColumn());
        add("groups", statements.getGroupsColumn());
    }
}
