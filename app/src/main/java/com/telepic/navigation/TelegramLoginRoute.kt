package com.telepic.navigation

/**
 * The standalone Telegram sign-in destination.
 *
 * It exists because Telegram is **optional** during onboarding: a user who tapped "Set up later"
 * then had no way to connect at all — Settings only ever offered sign-out, and the Cloud tab's
 * "not connected" state was a dead end with no action. Both now lead here.
 *
 * The screen hosts the same `TelegramStep` the onboarding flow uses, so the two paths cannot drift
 * apart, and it is reached from the account row in Settings and from the Cloud tab.
 */
object TelegramLoginRoute {
    const val ROUTE = "settings/telegram"
}
