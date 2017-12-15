/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

import React, {Component} from 'react';
import Header from "../common/Header";

import MuiThemeProvider from 'material-ui/styles/MuiThemeProvider';
import darkBaseTheme from 'material-ui/styles/baseThemes/darkBaseTheme';
import getMuiTheme from 'material-ui/styles/getMuiTheme';
const muiTheme = getMuiTheme(darkBaseTheme);

const errorTitleStyles = {
    color: "white",
    fontSize: 45
};

const errorMessageStyles = {
    color: "white",
    fontSize: 40
};

const errorContainerStyles = {
    textAlign: "center"
};

/**
 *  This component provide a basic 401 error page.
 */
class Error401 extends Component {
    render() {
        return <MuiThemeProvider muiTheme={muiTheme}>
            <Header/>
            <div style={errorContainerStyles}>
                <i class="fw fw-security fw-inverse fw-5x"></i>
                <h1 style={errorTitleStyles}>403 : Forbidden</h1>
                <h1 style={errorMessageStyles}>You have no permission to access this page</h1>
            </div>
        </MuiThemeProvider>;
    }
}
export default Error401;
