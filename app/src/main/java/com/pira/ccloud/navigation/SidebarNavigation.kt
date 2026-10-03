package com.pira.ccloud.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SidebarNavigation(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // Coming into the sidebar with the remote lands on the current section (as on Netflix), not
    // on whichever item is level with where the remote was
    val itemFocus = remember { AppScreens.screens.associate { it.route to FocusRequester() } }

    NavigationRail(
        modifier = Modifier
            .focusProperties { enter = { itemFocus[currentRoute] ?: FocusRequester.Default } }
            .focusGroup()
            .fillMaxHeight()
            .width(100.dp) // Increased width for better TV experience
            .padding(top = 24.dp, bottom = 24.dp), // Add padding top and bottom
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        header = {
            // Optional header content
            Spacer(modifier = Modifier.height(16.dp))
        }
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(28.dp) // Increased spacing between items
            ) {
                AppScreens.screens.filter { it.showSidebar }.forEach { screen ->
                    val isSelected = currentRoute == screen.route
                    // The item the remote is on shows clearly (white, larger, a light circle behind)
                    val interaction = remember { MutableInteractionSource() }
                    val isFocused by interaction.collectIsFocusedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isFocused) 1.2f else if (isSelected) 1.1f else 1f,
                        animationSpec = tween(durationMillis = 200),
                        label = "scale"
                    )
                    
                    val iconColor by animateColorAsState(
                        targetValue = if (isFocused) Color.White else if (isSelected) 
                            androidx.compose.material3.MaterialTheme.colorScheme.primary 
                        else 
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 200),
                        label = "iconColor"
                    )
                    
                    val textColor by animateColorAsState(
                        targetValue = if (isFocused) Color.White else if (isSelected) 
                            androidx.compose.material3.MaterialTheme.colorScheme.primary 
                        else 
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 200),
                        label = "textColor"
                    )

                    NavigationRailItem(
                        modifier = Modifier.focusRequester(itemFocus.getValue(screen.route)),
                        icon = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 22.dp) // Add horizontal padding
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp) // Increased size for better TV experience
                                        .scale(scale),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isFocused) {
                                        androidx.compose.material3.Surface(
                                            modifier = Modifier.size(52.dp),
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.22f),
                                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)
                                        ) {}
                                    } else if (isSelected) {
                                        androidx.compose.material3.Surface(
                                            modifier = Modifier.size(48.dp), // Increased size
                                            shape = CircleShape,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        ) {}
                                    }
                                    Icon(
                                        imageVector = screen.icon ?: Icons.Default.Movie, // Provide a fallback icon
                                        contentDescription = stringResource(screen.resourceId),
                                        tint = iconColor,
                                        modifier = Modifier.size(32.dp) // Increased size
                                    )
                                }
                                // Spacer(modifier = Modifier.height(2.dp)) // Increased spacing
                                Text(
                                    text = stringResource(screen.resourceId),
                                    color = textColor,
                                    fontSize = androidx.compose.material3.MaterialTheme.typography.labelMedium.fontSize, // Increased font size
                                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        },
                        label = null, // We're using custom label in icon
                        selected = isSelected,
                        interactionSource = interaction,
                        onClick = {
                            // Only navigate if we're not already on the selected screen
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    // Avoid multiple copies of the same destination when
                                    // reselecting the same item
                                    launchSingleTop = true
                                    // Restore state when reselecting a previously selected item
                                    restoreState = true
                                    // Pop up to the current destination to avoid building up a large stack
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                }
                            }
                        },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
            
            // Optional footer content like settings
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}