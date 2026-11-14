package com.example.colorosdebloater;

import android.os.Bundle;

interface IPackageService {
    oneway void destroy() = 16777114;
    Bundle execute(String operation, String packageName, int expectedUser) = 0;
}
