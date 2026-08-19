package com.planterior.helper.registration

import android.content.Context
import com.planterior.helper.feature.registration.RegistrationRepository

fun debugRegistrationRepository(
    context: Context,
    fallback: RegistrationRepository,
): RegistrationRepository = fallback

fun setDebugRegistrationDuplicateFixture(context: Context, enabled: Boolean) = Unit
