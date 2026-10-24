<?php
/**
 * Route definitions — modeled after Laravel's Route style
 * 
 * Format: [HTTP method, path pattern, handler name]
 * Paths support {param} placeholders
 */
return [
    ['GET',  '/',            'home'],
    ['GET',  '/post/{id}',   'show'],
    ['GET',  '/create',      'create'],
    ['POST', '/store',       'store'],
    ['GET',  '/delete/{id}', 'delete'],
    ['GET',  '/api/posts',   'apiPosts'],
];
